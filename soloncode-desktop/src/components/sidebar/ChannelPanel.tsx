import { useState, useCallback } from 'react';
import { Icon } from '../common/Icon';
import './ChannelPanel.css';

interface ChannelPanelProps {
  backendPort: number | null;
  sessionId?: string;
}

type TabType = 'wechat' | 'feishu' | 'dingtalk';

export function ChannelPanel({ backendPort, sessionId }: ChannelPanelProps) {
  const [activeTab, setActiveTab] = useState<TabType>('wechat');

  return (
    <div className="channel-panel">
      <div className="panel-header">
        <span className="panel-title">渠道绑定</span>
      </div>

      <div className="channel-tabs">
        <button className={`channel-tab${activeTab === 'wechat' ? ' active' : ''}`} onClick={() => setActiveTab('wechat')}>微信</button>
        <button className={`channel-tab${activeTab === 'feishu' ? ' active' : ''}`} onClick={() => setActiveTab('feishu')}>飞书</button>
        <button className={`channel-tab${activeTab === 'dingtalk' ? ' active' : ''}`} onClick={() => setActiveTab('dingtalk')}>钉钉</button>
      </div>

      <div className="channel-content">
        {activeTab === 'wechat' && <WeChatPanel backendPort={backendPort} sessionId={sessionId} />}
        {activeTab === 'feishu' && <FeishuPanel backendPort={backendPort} sessionId={sessionId} />}
        {activeTab === 'dingtalk' && <DingTalkPanel backendPort={backendPort} sessionId={sessionId} />}
      </div>
    </div>
  );
}

function WeChatPanel({ backendPort, sessionId }: { backendPort: number | null; sessionId?: string }) {
  const [qrCode, setQrCode] = useState<string | null>(null);
  const [status, setStatus] = useState<string>('');
  const [bound, setBound] = useState(false);
  const [loading, setLoading] = useState(false);

  const checkStatus = useCallback(async () => {
    if (!backendPort || !sessionId) return;
    try {
      const resp = await fetch(`http://localhost:${backendPort}/chat/wechat/status?sessionId=${encodeURIComponent(sessionId)}`);
      const data = await resp.json();
      if (data.data?.bound) setBound(true);
    } catch { /* ignore */ }
  }, [backendPort, sessionId]);

  const fetchQR = useCallback(async () => {
    if (!backendPort || !sessionId) return;
    setLoading(true);
    setStatus('scanning');
    try {
      const resp = await fetch(`http://localhost:${backendPort}/chat/wechat/qrcode?sessionId=${encodeURIComponent(sessionId)}`);
      const data = await resp.json();
      if (data.data?.qrcode_img_content) {
        setQrCode(data.data.qrcode_img_content);
        // 轮询扫码状态
        const poll = setInterval(async () => {
          try {
            const statusResp = await fetch(`http://localhost:${backendPort}/chat/wechat/qrcode/status?qrcode=${encodeURIComponent(data.data.qrcode_img_content)}&sessionId=${encodeURIComponent(sessionId)}`);
            const statusData = await statusResp.json();
            if (statusData.data?.status === 'confirmed') {
              clearInterval(poll);
              setBound(true);
              setStatus('bound');
              setQrCode(null);
            } else if (statusData.data?.status === 'error' || statusData.data?.status === 'expired') {
              clearInterval(poll);
              setStatus('expired');
              setQrCode(null);
            }
          } catch {
            clearInterval(poll);
            setStatus('error');
          }
        }, 2000);
        // 60s 超时
        setTimeout(() => { clearInterval(poll); setStatus('timeout'); setQrCode(null); }, 60000);
      } else {
        setStatus('error');
      }
    } catch {
      setStatus('error');
    } finally {
      setLoading(false);
    }
  }, [backendPort, sessionId]);

  const unbind = useCallback(async () => {
    if (!backendPort || !sessionId) return;
    try {
      await fetch(`http://localhost:${backendPort}/chat/wechat/unbind?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' });
      setBound(false);
      setStatus('');
    } catch { /* ignore */ }
  }, [backendPort, sessionId]);

  if (bound) {
    return (
      <div className="channel-bound">
        <Icon name="check" size={16} />
        <span>微信已绑定</span>
        <button className="unbind-btn" onClick={unbind}>解绑</button>
      </div>
    );
  }

  return (
    <div className="channel-bind">
      <p className="channel-desc">扫码绑定微信，在微信中与 AI 对话</p>
      {qrCode ? (
        <div className="qrcode-container">
          <img src={qrCode} alt="微信二维码" className="qrcode-img" />
          <p className="qrcode-hint">请使用微信扫码</p>
        </div>
      ) : (
        <button className="bind-btn" onClick={fetchQR} disabled={loading}>
          {loading ? '获取中...' : '获取二维码'}
        </button>
      )}
      {status === 'error' && <p className="channel-error">获取二维码失败</p>}
      {status === 'timeout' && <p className="channel-error">二维码已过期，请重新获取</p>}
    </div>
  );
}

function FeishuPanel({ backendPort, sessionId }: { backendPort: number | null; sessionId?: string }) {
  const [appId, setAppId] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [bound, setBound] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const bind = useCallback(async () => {
    if (!backendPort || !sessionId || !appId || !appSecret) return;
    setLoading(true);
    setError('');
    try {
      const resp = await fetch(`http://localhost:${backendPort}/chat/feishu/bind`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `sessionId=${encodeURIComponent(sessionId)}&appId=${encodeURIComponent(appId)}&appSecret=${encodeURIComponent(appSecret)}`,
      });
      const data = await resp.json();
      if (data.code === 200) {
        setBound(true);
      } else {
        setError(data.description || '绑定失败');
      }
    } catch {
      setError('连接失败');
    } finally {
      setLoading(false);
    }
  }, [backendPort, sessionId, appId, appSecret]);

  const unbind = useCallback(async () => {
    if (!backendPort || !sessionId) return;
    try {
      await fetch(`http://localhost:${backendPort}/chat/feishu/unbind?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' });
      setBound(false);
    } catch { /* ignore */ }
  }, [backendPort, sessionId]);

  if (bound) {
    return (
      <div className="channel-bound">
        <Icon name="check" size={16} />
        <span>飞书已绑定</span>
        <button className="unbind-btn" onClick={unbind}>解绑</button>
      </div>
    );
  }

  return (
    <div className="channel-bind">
      <p className="channel-desc">输入飞书机器人凭据绑定</p>
      <input className="channel-input" placeholder="App ID" value={appId} onChange={e => setAppId(e.target.value)} />
      <input className="channel-input" placeholder="App Secret" type="password" value={appSecret} onChange={e => setAppSecret(e.target.value)} />
      <button className="bind-btn" onClick={bind} disabled={loading || !appId || !appSecret}>
        {loading ? '绑定中...' : '绑定'}
      </button>
      {error && <p className="channel-error">{error}</p>}
    </div>
  );
}

function DingTalkPanel({ backendPort, sessionId }: { backendPort: number | null; sessionId?: string }) {
  const [appKey, setAppKey] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [bound, setBound] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const bind = useCallback(async () => {
    if (!backendPort || !sessionId || !appKey || !appSecret) return;
    setLoading(true);
    setError('');
    try {
      const resp = await fetch(`http://localhost:${backendPort}/chat/dingtalk/bind`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `sessionId=${encodeURIComponent(sessionId)}&appKey=${encodeURIComponent(appKey)}&appSecret=${encodeURIComponent(appSecret)}`,
      });
      const data = await resp.json();
      if (data.code === 200) {
        setBound(true);
      } else {
        setError(data.description || '绑定失败');
      }
    } catch {
      setError('连接失败');
    } finally {
      setLoading(false);
    }
  }, [backendPort, sessionId, appKey, appSecret]);

  const unbind = useCallback(async () => {
    if (!backendPort || !sessionId) return;
    try {
      await fetch(`http://localhost:${backendPort}/chat/dingtalk/unbind?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST' });
      setBound(false);
    } catch { /* ignore */ }
  }, [backendPort, sessionId]);

  if (bound) {
    return (
      <div className="channel-bound">
        <Icon name="check" size={16} />
        <span>钉钉已绑定</span>
        <button className="unbind-btn" onClick={unbind}>解绑</button>
      </div>
    );
  }

  return (
    <div className="channel-bind">
      <p className="channel-desc">输入钉钉机器人凭据绑定</p>
      <input className="channel-input" placeholder="AppKey" value={appKey} onChange={e => setAppKey(e.target.value)} />
      <input className="channel-input" placeholder="App Secret" type="password" value={appSecret} onChange={e => setAppSecret(e.target.value)} />
      <button className="bind-btn" onClick={bind} disabled={loading || !appKey || !appSecret}>
        {loading ? '绑定中...' : '绑定'}
      </button>
      {error && <p className="channel-error">{error}</p>}
    </div>
  );
}
