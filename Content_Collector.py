import os
import re
from typing import Union, Iterable, List, Dict
from langextract.data import Document

class DocumentCollector:
    """收集文檔內容並轉換為LangExtract所需的Document對象"""
    
    def __init__(self, file_path: str, extensions: List[str]):
        self.file_path = file_path
        self.extensions = extensions
        self.documents: List[Document] = []
    
    def _enhanced_regex_extraction(self, documents: List[Dict]) -> List[Dict]:
        """Enhanced regex-based extraction with better patterns"""
        
        extracted_docs = []
        
        for doc in documents:
            metadata = {
                'service': 'unknown',
                'version': 'unknown',
                'doc_type': 'reference', 
                'rate_limits': [],
                'deprecated': False
            }
            
            title = doc.get('title', '')
            content = doc['content']
            
            # Extract service name from title
            service_match = re.search(r'([\w\s]+(?:API|Service))', title)
            if service_match:
                metadata['service'] = service_match.group(1).strip()
            
            # Extract version number
            version_match = re.search(r'v?([\d.]+)', title)
            if version_match:
                metadata['version'] = version_match.group(1)
            
            # Determine document type
            if 'troubleshooting' in title.lower():
                metadata['doc_type'] = 'troubleshooting'
            elif 'guide' in title.lower():
                metadata['doc_type'] = 'guide'
            else:
                metadata['doc_type'] = 'reference'
            
            # Extract rate limits
            rate_matches = re.findall(r'(\d+)\s*(?:requests?|req)[/\s]*(?:per\s*)?min', content.lower())
            metadata['rate_limits'] = [f"{r} req/min" for r in rate_matches]
            
            # Check for deprecation
            if 'deprecated' in content.lower():
                metadata['deprecated'] = True
            
            extracted_docs.append({
                'id': doc['id'],
                'title': doc['title'], 
                'content': doc['content'],
                'metadata': metadata
            })
        
        return extracted_docs

    def find_files_with_extensions(self) -> List[str]:
        """
        遞歸查找指定副檔名的文件
        
        :return: 找到的文件路徑列表
        """
        found_files = []

        if not os.path.isdir(self.file_path):
            raise FileNotFoundError(f"找不到目錄: {os.path.abspath(self.file_path)}")

        for dirpath, _, filenames in os.walk(self.file_path):
            for filename in filenames:
                if any(filename.endswith(ext) for ext in self.extensions):
                    file_path = os.path.join(dirpath, filename)
                    found_files.append(file_path)
                    print(f"找到文件: {file_path}")

        print(f"找到程式文件共 {len(found_files)} 個。")
        return found_files

    def load_file_content(self, file_path: str) -> str:
        """
        安全地載入單個文件內容
        
        :param file_path: 文件路徑
        :return: 文件內容，出錯時返回空字符串
        """
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()
                print(f"✅ 成功載入: {file_path} ({len(content)} 字符)")
                return content
        except UnicodeDecodeError:
            # 嘗試其他編碼
            try:
                with open(file_path, 'r', encoding='gbk') as f:
                    content = f.read()
                    print(f"✅ 成功載入 (GBK編碼): {file_path}")
                    return content
            except Exception as e:
                print(f"❌ 編碼錯誤: {file_path} - {e}")
                return ""
        except FileNotFoundError:
            print(f"❌ 找不到檔案: {file_path}")
            return ""
        except PermissionError:
            print(f"❌ 沒有權限讀取檔案: {file_path}")
            return ""
        except Exception as e:
            print(f"❌ 讀取檔案 {file_path} 時發生錯誤: {e}")
            return ""

    def create_langextract_document(self, file_path: str, content: str) -> Document:
        """
        創建LangExtract Document對象
        
        :param file_path: 文件路徑
        :param content: 文件內容
        :return: LangExtract Document對象
        """
        # 使用相對路徑作為document_id，更簡潔
        relative_path = os.path.relpath(file_path, self.file_path)
        
        # 創建Document時，確保參數正確
        doc = Document(
            text=content,
            document_id=relative_path,
            # 可以添加metadata
            # metadata={
            #     "full_path": file_path,
            #     "extension": Path(file_path).suffix,
            #     "size": len(content)
            # }
        )
        return doc

    def collect_documents(self) -> List[Document]:
        """
        收集所有文檔並轉換為LangExtract Document對象
        
        :return: Document對象列表
        """
        # 清空之前的結果
        self.documents = []
        
        found_files = self.find_files_with_extensions()
        print(f"準備載入 {len(found_files)} 個檔案...")

        successful_count = 0
        
        for file_path in found_files:
            content = self.load_file_content(file_path)
            
            # 只處理非空內容
            if content and content.strip():
                try:
                    doc = self.create_langextract_document(file_path, content)
                    self.documents.append(doc)
                    successful_count += 1
                    
                    # 顯示文件內容預覽
                    preview = content[:100].replace('\n', '\\n')
                    print(f"📄 文檔創建成功: {os.path.basename(file_path)} - 預覽: {preview}...")
                    
                except Exception as e:
                    print(f"❌ 創建文檔失敗 {file_path}: {e}")
                    continue
            else:
                print(f"⚠️ 跳過空文件: {file_path}")

        print(f"\n✅ 成功處理 {successful_count}/{len(found_files)} 個文件")
        print(f"📋 總共創建 {len(self.documents)} 個Document對象")
        
        return self.documents

    def get_documents_for_langextract(self) -> List[Document]:
        """
        獲取適用於LangExtract的Document列表
        
        :return: 適用於text_or_documents參數的Document列表
        """
        if not self.documents:
            self.collect_documents()
        
        # 驗證Document對象
        valid_documents = []
        for doc in self.documents:
            if hasattr(doc, 'text') and hasattr(doc, 'document_id'):
                if doc.text and doc.text.strip():
                    valid_documents.append(doc)
                else:
                    print(f"⚠️ 跳過空內容文檔: {doc.document_id}")
            else:
                print(f"❌ 無效的Document對象: {doc}")
        
        print(f"📊 最終有效文檔數量: {len(valid_documents)}")
        return valid_documents
