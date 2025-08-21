"""
Agentic RAG pipeline with LangChain, LangGraph, Graphiti
"""
import asyncio
import os
import uuid
from datetime import datetime, timezone
from typing import TypedDict, Annotated

from typing import TYPE_CHECKING


from langchain_openai import ChatOpenAI
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import StateGraph, END, START, add_messages
from langchain_core.messages import AIMessage, SystemMessage, HumanMessage
from graphiti_core import Graphiti
from graphiti_core.nodes import EpisodeType
from graphiti_core.edges import EntityEdge

class RagState(TypedDict):
    messages: Annotated[list, add_messages]
    user_name: str
    user_node_uuid: str


async def build_graphiti_client():
    neo4j_uri = os.environ.get("NEO4J_URI", "bolt://localhost:7687")
    neo4j_user = os.environ.get("NEO4J_USER", "neo4j")
    neo4j_password = os.environ.get("NEO4J_PASSWORD", "password")
    return Graphiti(neo4j_uri, neo4j_user, neo4j_password)


async def chatbot(state: RagState, client: Graphiti, llm) -> dict:
    facts_string = None
    last_message = None
    if len(state["messages"]) > 0:
        last_message = state["messages"][-1]
        graphiti_query = f'{state["user_name"]}: {last_message.content}'
        edge_results = await client.search(graphiti_query, center_node_uuid=state["user_node_uuid"], num_results=5)
        facts_string = "-" + "\n-".join([edge.fact for edge in edge_results])

    system_message = SystemMessage(
        content=f"Use the following facts for context:\n{facts_string or 'No facts available'}"
    )
    messages = [system_message] + state["messages"]

    response = await llm.ainvoke(messages)

    if last_message:
        asyncio.create_task(
            client.add_episode(
                name="Chatbot Response",
                episode_body=f"{state['user_name']}:{last_message.content}\nAgent:{response.content}",
                source=EpisodeType.message,
                reference_time=datetime.now(timezone.utc),
                source_description="Agentic Rag Bot",
            )
        )
    return {"messages": [response]}


async def init_agent():
    # init llm
    llm = ChatOpenAI(model="gpt-5-mini", temperature=0)
    memory = MemorySaver()
    client = await build_graphiti_client()

    graph_builder = StateGraph(RagState)

    async def start_fn(state: RagState):
        return {"messages": [AIMessage(content="Hello, how can I help you today?")]}

    async def agent_fn(state: RagState):
        return await chatbot(state, client, llm)

    graph_builder.add_node("start", start_fn)
    graph_builder.add_node("agent", agent_fn)
    graph_builder.add_edge(START, "start")
    graph_builder.add_edge("start", "agent")
    graph_builder.add_edge("agent", END)

    return graph_builder.compile(checkpointer=memory)


if __name__ == "__main__":
    async def main():
        agent_graph = await init_agent()
        user_name = "User"
        # assume we have created a user node previously
        user_node_uuid = str(uuid.uuid4())
        state: RagState = {
            "messages": [HumanMessage(content="請幫我解釋這段代碼如何運行？")],
            "user_name": user_name,
            "user_node_uuid": user_node_uuid,
        }
        async for event in agent_graph.astream(state):
            for value in event.values():
                if isinstance(value, dict) and "messages" in value:
                    msgs = value.get("messages", [])
                    if msgs:
                        last = msgs[-1]
                        print(getattr(last, "content", last))

    asyncio.run(main())