import { useState } from 'react';
import './ThinkBlock.css';

interface ThinkBlockProps {
  content: string;
}

export function ThinkBlock({ content }: ThinkBlockProps) {
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
          {content}
        </div>
      )}
    </div>
  );
}
