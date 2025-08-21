"""
FastAPI service integrating Chunking pipeline + Graphiti (Agentic RAG)
"""
from fastapi import FastAPI, UploadFile, File, Form
from typing import List
import asyncio
import os
from pathlib import Path

from chuncking import create_chunking_graph, GraphState
from agentic_rag import init_agent, RagState

app = FastAPI(title="Agnetic RAG API")

# Initialize global components
chunking_app = create_chunking_graph()

@app.post("/upload")
async def upload_code():
    """批量載入 docs/ 目錄內符合副檔名的文件，並送入 chunking pipeline"""
    docs_dir = Path("docs")
    if not docs_dir.exists():
        return {"status": "error", "message": "docs/ 資料夾不存在"}

    state: GraphState = {
        "file_path": str(docs_dir),
        "documents": [],
        "analysis_results": {},
        "chunks": []
    }
    final_state = chunking_app.invoke(state)
    num_chunks = len(final_state.get("chunks", []))
    return {"status": "ok", "scanned_dir": str(docs_dir), "chunks": num_chunks}


@app.post("/query")
async def query_rag(question: str = Form(...)):
    """使用 Graphiti + LangGraph pipeline 處理查詢 (RAG)"""
    agent_graph = await init_agent()
    rag_state: RagState = {
        "messages": [{"role": "user", "content": question}],
        "user_name": "user",
        "user_node_uuid": "dummy-uuid"
    }
    answer = ""
    async for event in agent_graph.astream(rag_state):
        for value in event.values():
            if isinstance(value, dict) and "messages" in value:
                msgs = value.get("messages", [])
                if msgs:
                    last = msgs[-1]
                    answer = getattr(last, "content", str(last))
    return {"answer": answer}


if __name__ == "__main__":
    """簡單的測試入口，用來直接執行 upload_code 函數"""
    import asyncio

    async def run_test():
        await upload_code()

    asyncio.run(run_test())