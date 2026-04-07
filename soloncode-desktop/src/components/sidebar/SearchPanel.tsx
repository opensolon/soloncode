import { useState } from 'react';
import { Icon } from '../common/Icon';
import './SearchPanel.css';

interface SearchResult {
  file: string;
  line: number;
  column: number;
  text: string;
  matchText: string;
}

interface SearchPanelProps {
  onSearch: (query: string) => Promise<SearchResult[]>;
  onResultClick: (result: SearchResult) => void;
}

export function SearchPanel({ onSearch, onResultClick }: SearchPanelProps) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [matchCase, setMatchCase] = useState(false);
  const [matchWholeWord, setMatchWholeWord] = useState(false);
  const [useRegex, setUseRegex] = useState(false);

  async function handleSearch() {
    if (!query.trim()) return;
    setIsSearching(true);
    try {
      const searchResults = await onSearch(query);
      setResults(searchResults);
    } finally {
      setIsSearching(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Enter') {
      handleSearch();
    }
  }

  return (
    <div className="search-panel">
      <div className="panel-header">
        <span className="panel-title">搜索</span>
      </div>
      <div className="search-input-container">
        <div className="search-input-wrapper">
          <Icon name="search" size={16} className="search-icon" />
          <input
            type="text"
            className="search-input"
            placeholder="搜索..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
          />
        </div>
        <div className="search-options">
          <button
            className={`search-option${matchCase ? ' active' : ''}`}
            title="区分大小写"
            onClick={() => setMatchCase(!matchCase)}
          >
            Aa
          </button>
          <button
            className={`search-option${matchWholeWord ? ' active' : ''}`}
            title="全词匹配"
            onClick={() => setMatchWholeWord(!matchWholeWord)}
          >
            W
          </button>
          <button
            className={`search-option${useRegex ? ' active' : ''}`}
            title="使用正则表达式"
            onClick={() => setUseRegex(!useRegex)}
          >
            .*
          </button>
        </div>
      </div>
      <div className="search-results">
        {isSearching ? (
          <div className="search-loading">搜索中...</div>
        ) : results.length > 0 ? (
          <>
            <div className="results-header">{results.length} 个结果</div>
            {results.map((result, index) => (
              <div
                key={`${result.file}-${result.line}-${index}`}
                className="search-result-item"
                onClick={() => onResultClick(result)}
              >
                <div className="result-file">{result.file}</div>
                <div className="result-line">
                  <span className="line-number">{result.line}</span>
                  <span className="line-text">{result.text}</span>
                </div>
              </div>
            ))}
          </>
        ) : query && !isSearching ? (
          <div className="no-results">未找到结果</div>
        ) : null}
      </div>
    </div>
  );
}
