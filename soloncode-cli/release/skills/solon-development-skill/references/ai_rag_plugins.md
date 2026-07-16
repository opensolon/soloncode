# AI RAG Plugins — 文档加载 / 向量库 / 联网搜索

> 适用场景：选择 RAG 文档加载器、向量库后端、联网搜索插件。
>
> 目标版本：4.0.3。Embedding / Reranking / 检索流水线见 `ai_chat_rag_mcp.md`。
>
> **无**独立 `solon-ai-rag` 坐标；核心在 `solon-ai-core`，插件按需引入。

## Document Loaders

| Artifact | Format | Loader 类 |
|---|---|---|
| `solon-ai-load-pdf` | PDF | `PdfLoader` |
| `solon-ai-load-word` | Word (.doc/.docx) | `WordLoader` |
| `solon-ai-load-excel` | Excel (.xls/.xlsx) | `ExcelLoader` |
| `solon-ai-load-html` | HTML | `HtmlSimpleLoader` |
| `solon-ai-load-markdown` | Markdown | `MarkdownLoader` |
| `solon-ai-load-ppt` | PowerPoint (.ppt/.pptx) | `PptLoader` |

```java
// 各 Loader 用法一致；写入用 save，不要用 insert
PdfLoader loader = new PdfLoader(new File("data.pdf"));
List<Document> docs = loader.load();
repository.save(docs);
```

## Vector Repositories

| Artifact | Backend |
|---|---|
| `solon-ai-repo-milvus` | Milvus |
| `solon-ai-repo-pgvector` | PgVector |
| `solon-ai-repo-elasticsearch` | Elasticsearch |
| `solon-ai-repo-redis` | Redis |
| `solon-ai-repo-qdrant` | Qdrant |
| `solon-ai-repo-chroma` | Chroma |
| `solon-ai-repo-weaviate` | Weaviate |
| `solon-ai-repo-dashvector` | DashVector |

内存实现：`InMemoryRepository`（在 `solon-ai-core` 内，无需额外 artifact）。

## WebSearch — 联网搜索

| Artifact | 搜索引擎 | Repository 类 |
|---|---|---|
| `solon-ai-search-baidu` | 百度搜索 | `BaiduWebSearchRepository` |
| `solon-ai-search-bocha` | Bocha 搜索 | `BochaWebSearchRepository` |
| `solon-ai-search-tavily` | Tavily 搜索 | `TavilyWebSearchRepository` |

## 依赖速查

| Artifact | Description |
|---|---|
| `solon-ai-core` / `solon-ai` | 含 Embedding/Reranking/InMemoryRepository |
| `solon-ai-load-*` | 文档加载器 |
| `solon-ai-repo-*` | 向量库 |
| `solon-ai-search-*` | 联网搜索 |

> Chat / Tool / MCP / 基础 RAG 流水线见 `ai_chat_rag_mcp.md`。