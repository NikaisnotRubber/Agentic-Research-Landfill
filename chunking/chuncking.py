import ast
import os
import shutil
from typing import List, Dict, Any, TypedDict, Annotated

from langchain_community.document_loaders import DirectoryLoader, TextLoader
from langchain_core.documents import Document
from langchain_experimental.text_splitter import SemanticChunker
from langchain_huggingface import HuggingFaceEmbeddings
# from langchain.text_splitter import RecursiveCharacterTextSplitter, Language
from langchain_text_splitters.character import RecursiveCharacterTextSplitter
from langchain_text_splitters.base import Language
from langgraph.graph import StateGraph, END

import asyncio
from datetime import datetime, timezone
from graphiti_core import Graphiti
from graphiti_core.nodes import EpisodeType
from graphiti_core.utils.bulk_utils import RawEpisode
from graphiti_core.driver.neo4j_driver import Neo4jDriver

# 0. Global def
extensions = [".py", ".java", ".groovy", ".kt", ".js", ".ts"]


# --- 1. Helper Class for Code Analysis ---
class CodeAnalyzer(ast.NodeVisitor):
    """
    Analyzes a Python script using AST to find dependencies for each function and class.
    使用 AST 分析 Python 腳本，找出每個函數和類的依賴項。
    """
    def __init__(self, source_code):
        self.source_code = source_code
        self.imports = {}  # alias -> module
        self.dependencies = {}  # function/class name -> set of used modules
        self.current_scope = None

    def visit_Import(self, node):
        for alias in node.names:
            self.imports[alias.asname or alias.name] = alias.name.split('.')[0]
        self.generic_visit(node)

    def visit_ImportFrom(self, node):
        module = node.module.split('.')[0] if node.module else ''
        for alias in node.names:
            self.imports[alias.asname or alias.name] = module
        self.generic_visit(node)

    def _track_dependencies(self, node):
        scope_name = node.name
        self.current_scope = scope_name
        self.dependencies[scope_name] = set()
        self.generic_visit(node)
        self.current_scope = None

    def visit_FunctionDef(self, node):
        self._track_dependencies(node)

    def visit_AsyncFunctionDef(self, node):
        self._track_dependencies(node)

    def visit_ClassDef(self, node):
        self._track_dependencies(node)

    def visit_Name(self, node):
        if self.current_scope and node.id in self.imports:
            self.dependencies[self.current_scope].add(self.imports[node.id])

    def visit_Attribute(self, node):
        # This handles cases like `pd.DataFrame` or `np.array`
        if self.current_scope and isinstance(node.value, ast.Name) and node.value.id in self.imports:
            self.dependencies[self.current_scope].add(self.imports[node.value.id])
        self.generic_visit(node)

    def analyze(self):
        tree = ast.parse(self.source_code)
        self.visit(tree)
        # Convert sets to lists for JSON serialization
        return {k: sorted(list(v)) for k, v in self.dependencies.items()}


# --- 2. LangGraph State Definition ---
class GraphState(TypedDict):
    """
    Represents the state of our graph.
    代表我們圖的狀態。
    """
    file_path: str
    documents: List[Document]
    analysis_results: Dict[str, List[str]]
    chunks: List[Document]

# --- 3. LangGraph Nodes ---
def load_code_node(state: GraphState) -> GraphState:
    """
    Loads all specified script files from the directory using a recursive tree walk.
    使用遞歸樹遍歷從目錄中加載所有指定的腳本文件。
    """
    print("---正在從目錄樹中加載所有腳本文件---")
    dir_path = state['file_path']
    if not os.path.isdir(dir_path):
        raise FileNotFoundError(f"找不到目錄: {dir_path}")

    documents = []
    
    print(f"正在從 {dir_path} 以樹狀搜尋載入文件...")
    
    file_paths = []
    for root, _, files in os.walk(dir_path):
        for file in files:
            if any(file.endswith(ext) for ext in extensions):
                file_paths.append(os.path.join(root, file))

    for file_path in file_paths:
        try:
            # Each file is loaded using TextLoader
            loader = TextLoader(file_path, encoding='utf-8')
            documents.extend(loader.load())
        except Exception as e:
            print(f"載入文件 {file_path} 時出錯: {e}")

    if not documents:
        print("警告: 在指定目錄中未找到任何有效文件。")

    print(f"成功載入 {len(documents)} 個文件。")
    state['documents'] = documents
    state['analysis_results'] = {}
    state['chunks'] = []
    return state


def analyze_code_node(state: GraphState) -> GraphState:
    """
    Analyzes the loaded code to extract dependencies for functions and classes.
    Only analyzes Python files.
    分析已加載的代碼，提取函數和類的依賴關係 (僅限 Python 文件)。
    """
    print("---正在進行靜態誓法分析 (AST)---  ") 
    documents = state['documents']
    all_analysis_results = {}

    for doc in documents:
        file_path = doc.metadata.get('source', '')
        if any(file_path.endswith(ext) for ext in extensions):
            try:
                print(f"正在分析: {file_path}")
                analyzer = CodeAnalyzer(doc.page_content)
                analysis_results = analyzer.analyze()
                # Simple merge, may have collisions if function/class names are not unique across files
                all_analysis_results.update(analysis_results)
            except Exception as e:
                print(f"無法分析 {file_path}: {e}")
    
    print(f"分析完成，找到 {len(all_analysis_results)} 個具備依贅關係的對象。" )
    state['analysis_results'] = all_analysis_results
    return state

def chunk_code_node(state: GraphState) -> GraphState:
    """
    Splits the code into chunks using a hybrid strategy.
    使用混合策略將代碼分割成塊。
    """
    print("---正在進行程式码切割---")
    documents = state['documents']

    # Strategy 1: Structure-aware chunking by language
    # 策略一: 基於程式語言的結構感知切割
    # Note: This example focuses on Python, but you could add logic to select a splitter based on file extension
    python_splitter = RecursiveCharacterTextSplitter.from_language(
        language=Language.PYTHON, chunk_size=1000, chunk_overlap=100
    )
    initial_chunks = python_splitter.split_documents(documents)
    print(f"初步結構化切割完成，生成 {len(initial_chunks)} 個區塊。" )

    # Strategy 2: Semantic chunking for oversized chunks
    # 策略二: 對超大區塊進行語義切割
    print("初始化 Qwen/Qwen2-1.5B-Instruct 模型...")
    embeddings = HuggingFaceEmbeddings(
        model_name="Qwen/Qwen3-Embedding-0.6B",
        model_kwargs={"device": "cpu"}  # Use 'cuda' if GPU is available
    )
    
    semantic_splitter = SemanticChunker(
        embeddings,
        breakpoint_threshold_type="percentile" # More robust threshold
    )

    final_chunks = []
    # Set a threshold for when to apply semantic chunking
    SEMANTIC_CHUNK_THRESHOLD = 1500 # characters

    for chunk in initial_chunks:
        if len(chunk.page_content) > SEMANTIC_CHUNK_THRESHOLD:
            print(f"區塊過長 (長度 {len(chunk.page_content)} 來自 {chunk.metadata.get('source')}) ，正在進行語義切割...")
            semantic_sub_chunks = semantic_splitter.create_documents([chunk.page_content])
            # Add metadata from the parent chunk
            for sub_chunk in semantic_sub_chunks:
                sub_chunk.metadata = chunk.metadata.copy()
            final_chunks.extend(semantic_sub_chunks)
        else:
            final_chunks.append(chunk)
            
    print(f"最終切割完成，共生成 {len(final_chunks)} 個區塊。" )
    state['chunks'] = final_chunks
    return state


def enrich_chunks_node(state: GraphState) -> GraphState:
    """
    Enriches each chunk with dependency information.
    """
    print("---正在國充區塊元數據與內容---")
    analysis_results = state['analysis_results']
    chunks = state['chunks']
    enriched_chunks = []

    for chunk in chunks:
        # Only try to enrich Python code chunks
        if not chunk.metadata.get('source', '').endswith('.py'):
            enriched_chunks.append(chunk)
            continue

        content = chunk.page_content
        first_line = content.lstrip().split('\n')[0]
        
        obj_name = None
        if first_line.startswith("def "):
            obj_name = first_line.split("def ")[1].split("(")[0].strip()
            chunk.metadata['type'] = 'function'
        elif first_line.startswith("class "):
            obj_name = first_line.split("class ")[1].split(":")[0].split("(")[0].strip()
            chunk.metadata['type'] = 'class'
        else:
            chunk.metadata['type'] = 'script_block'

        dependencies = []
        if obj_name and obj_name in analysis_results:
            dependencies = analysis_results[obj_name]
        
        chunk.metadata['dependencies'] = dependencies

        if dependencies:
            dep_comment = f"\n\n# DEPENDENCIES: {', '.join(dependencies)}"
            chunk.page_content += dep_comment
        
        enriched_chunks.append(chunk)
    
    print("處理完成。" )
    state['chunks'] = enriched_chunks
    return state

def send_to_graphiti_node(state: GraphState) -> GraphState:
    """
    將最終切片 (chunks) 輸入到 Graphiti 知識圖，作為 RAG 用。
    """
    print("---正在將切片匯入 Graphiti ---")
    async def _ingest(chunks):
        driver = Neo4jDriver(uri="bolt://localhost:7687", user="neo4j", password="password")
        client = Graphiti(graph_driver=driver)
        episodes = []
        for i, chunk in enumerate(chunks):
            episodes.append(RawEpisode(
                name=f"chunk-{i}",
                content=chunk.page_content,
                source_description=chunk.metadata.get("source", "code_chunk"),
                source=EpisodeType.text,
                reference_time=datetime.now(timezone.utc)
            ))
        if episodes:
            await client.add_episode_bulk(episodes)
            print(f"已匯入 {len(episodes)} 個切片到 Graphiti")
    asyncio.run(_ingest(state["chunks"]))
    return state


# --- 4. Graph Assembly ---
def create_chunking_graph():
    """
    Creates and compiles the LangGraph workflow.
    創建並編譯 LangGraph 工作流程。
    """
    workflow = StateGraph(GraphState)

    # Add nodes
    workflow.add_node("load_code", load_code_node)
    workflow.add_node("analyze_code", analyze_code_node)
    workflow.add_node("chunk_code", chunk_code_node)
    workflow.add_node("enrich_chunks", enrich_chunks_node)
    workflow.add_node("send_to_graphiti", send_to_graphiti_node)

    # Define edges
    workflow.set_entry_point("load_code")
    workflow.add_edge("load_code", "analyze_code")
    workflow.add_edge("analyze_code", "chunk_code")
    workflow.add_edge("chunk_code", "enrich_chunks")
    workflow.add_edge("enrich_chunks", "send_to_graphiti")
    workflow.add_edge("send_to_graphiti", END)
    app = workflow.compile()
    print("✅ 程式码切片工作流 (Graph) 編譯完成！")
    return app

# --- 5. Example Usage ---
if __name__ == '__main__':
    # The directory containing the code files to be chunked.
    # Point this to the 'docs' directory which contains your code.
    docs_dir = "../docs"

    if not os.path.isdir(docs_dir):
        print(f"錯誤: 找不到目錄 '{docs_dir}'.")
        print("請創建該目錄並將您的源代碼文件放入其中。" )
    else:
        # Create and run the graph
        chunking_app = create_chunking_graph()
        inputs: GraphState = {
            "file_path": docs_dir,
            "documents": [],
            "analysis_results": {},
            "chunks": []
        }
        final_state = chunking_app.invoke(inputs)

        if final_state and final_state.get('chunks'):
            final_chunks = final_state['chunks']
            print("\n--- 最終切片結果預覽 ---")
            for i, chunk in enumerate(final_chunks):
                print(f"\n--- [\u5340 {i+1}] ---")
                print("\u4F86来源 (Source):", chunk.metadata.get('source'))
                print("\u5167容 (Content):")
                print(chunk.page_content)
                print("\n\u5143數據 (Metadata):")
                print(chunk.metadata)
                print("-" * 20)
        else:
            print("\u5DE5作流未返回任何切片。" )
