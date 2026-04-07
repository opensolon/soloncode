import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import type { Theme } from '../types';
import './ActionBlock.css';

interface ActionBlockProps {
  text: string;
  toolName?: string;
  args?: Record<string, unknown>;
  theme?: Theme;
}

export function ActionBlock({ text, toolName, args, theme }: ActionBlockProps) {
  const [isExpanded, setIsExpanded] = useState(false);

  return (
    <div className="action-block">
      <div
        className="action-block-header"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <span className="action-block-icon">⚡</span>
        <span className="action-block-title">{toolName || '工具执行'}</span>
        <span className={`action-block-arrow ${isExpanded ? 'expanded' : ''}`}>▼</span>
      </div>
      {isExpanded && (
        <div className="action-block-content">
          {args && (
            <div className="action-block-args">
              <div className="action-block-args-label">参数</div>
              <pre>{JSON.stringify(args, null, 2)}</pre>
            </div>
          )}
          <div className="action-block-result">
            <ReactMarkdown
              remarkPlugins={[remarkBreaks]}
              components={{
                code({ node, inline, className, children, ...props }: any) {
                  const match = /language-(\w+)/.exec(className || '');
                  return !inline && match ? (
                    <SyntaxHighlighter
                      style={theme === 'dark' ? oneDark : oneLight}
                      language={match[1]}
                      PreTag="div"
                      {...props}
                    >
                      {String(children).replace(/\n$/, '')}
                    </SyntaxHighlighter>
                  ) : (
                    <code className={className} {...props}>
                      {children}
                    </code>
                  );
                }
              }}
            >
              {text || '执行完成'}
            </ReactMarkdown>
          </div>
        </div>
      )}
    </div>
  );
}
