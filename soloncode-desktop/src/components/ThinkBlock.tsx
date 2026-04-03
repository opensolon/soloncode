import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkBreaks from 'remark-breaks';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import type { Theme } from '../types';
import './ThinkBlock.css';

interface ThinkBlockProps {
  content: string;
  theme?: Theme;
}

export function ThinkBlock({ content, theme }: ThinkBlockProps) {
  const [isExpanded, setIsExpanded] = useState(false);

  return (
    <div className="think-block">
      <div
        className="think-header"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <span className="think-title">思考过程</span>
        <span className={`think-arrow ${isExpanded ? 'expanded' : ''}`}>▼</span>
      </div>
      {isExpanded && (
        <div className="think-content">
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
            {content}
          </ReactMarkdown>
        </div>
      )}
    </div>
  );
}
