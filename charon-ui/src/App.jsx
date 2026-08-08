import { useState, useEffect, useRef } from 'react';
import { 
  Clock, Zap, Skull, Server, AlertCircle, 
  Send, Plus, RefreshCw, CheckCircle, Database,
  User, Play, Trash2, LayoutDashboard, Settings, Layers, Menu
} from 'lucide-react';
import { 
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer 
} from 'recharts';
import './index.css';

const API_BASE = 'http://localhost:8080/jobs';
const MAX_HISTORY = 20;

const CustomTooltip = ({ active, payload, label }) => {
  if (active && payload && payload.length) {
    return (
      <div className="custom-tooltip">
        <p style={{ color: 'var(--text-secondary)', marginBottom: '0.5rem' }}>{label}</p>
        {payload.map(p => (
          <div key={p.dataKey} className="custom-tooltip-item">
            <span style={{ color: p.color, fontWeight: 'bold' }}>{p.name}:</span>
            <span style={{ color: '#fff' }}>{p.value}</span>
          </div>
        ))}
      </div>
    );
  }
  return null;
};

const DeltaPill = ({ current, previous }) => {
  if (previous === undefined || previous === null) return <span className="delta-pill">No history</span>;
  const diff = current - previous;
  if (diff === 0) return <span className="delta-pill">No change</span>;
  if (diff > 0) return <span className="delta-pill positive">+{diff} since last check</span>;
  return <span className="delta-pill negative">{diff} since last check</span>;
};

function App() {
  const [queueStatus, setQueueStatus] = useState({
    pendingCount: 0,
    leasedCount: 0,
    deadCount: 0,
    leasedJobs: []
  });
  const [deadLetters, setDeadLetters] = useState([]);
  const [history, setHistory] = useState([]);
  
  const [apiConnected, setApiConnected] = useState(true);
  const [now, setNow] = useState(Date.now());
  const [lastRefreshed, setLastRefreshed] = useState(new Date().toLocaleTimeString());
  
  const [replayingIds, setReplayingIds] = useState(new Set());
  const [successIds, setSuccessIds] = useState(new Set());
  
  // Previous values for KPI pulse animations
  const prevPending = useRef(0);
  const prevLeased = useRef(0);
  const prevDead = useRef(0);
  
  const [pulsePending, setPulsePending] = useState(false);
  const [pulseLeased, setPulseLeased] = useState(false);
  const [pulseDead, setPulseDead] = useState(false);

  // Form states
  const [payload, setPayload] = useState('{"task": "send_email"}');
  const [priority, setPriority] = useState(5);
  const [delaySecs, setDelaySecs] = useState(0);
  const [seeding, setSeeding] = useState(false);

  // Fast timer for live lease countdown & clock updates
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  // API Polling every 2 seconds
  useEffect(() => {
    const fetchData = async () => {
      try {
        const [statusRes, deadRes] = await Promise.all([
          fetch(`${API_BASE}/queue/status`),
          fetch(`${API_BASE}/dead-letters`)
        ]);

        if (!statusRes.ok || !deadRes.ok) throw new Error("API Error");

        const statusData = await statusRes.json();
        const deadData = await deadRes.json();

        // Trigger animations if values changed
        if (statusData.pendingCount !== prevPending.current) {
          setPulsePending(true); setTimeout(() => setPulsePending(false), 1000);
          prevPending.current = statusData.pendingCount;
        }
        if (statusData.leasedCount !== prevLeased.current) {
          setPulseLeased(true); setTimeout(() => setPulseLeased(false), 1000);
          prevLeased.current = statusData.leasedCount;
        }
        if (statusData.deadCount !== prevDead.current) {
          setPulseDead(true); setTimeout(() => setPulseDead(false), 1000);
          prevDead.current = statusData.deadCount;
        }

        setQueueStatus(statusData);
        setDeadLetters(deadData);
        setApiConnected(true);
        const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        setLastRefreshed(timeStr);
        
        // Update history for chart
        setHistory(prev => {
          const newData = {
            time: timeStr,
            pending: statusData.pendingCount,
            inFlight: statusData.leasedCount,
            dead: statusData.deadCount
          };
          const updated = [...prev, newData];
          if (updated.length > MAX_HISTORY) return updated.slice(updated.length - MAX_HISTORY);
          return updated;
        });
        
      } catch (err) {
        setApiConnected(false);
      }
    };

    fetchData();
    const interval = setInterval(fetchData, 2000);
    return () => clearInterval(interval);
  }, []);

  const handleReplay = async (id) => {
    setReplayingIds(prev => new Set(prev).add(id));
    try {
      const res = await fetch(`${API_BASE}/dead-letters/${id}/replay`, { method: 'POST' });
      if (res.ok) {
        setSuccessIds(prev => new Set(prev).add(id));
        setTimeout(() => {
          setSuccessIds(prev => { const next = new Set(prev); next.delete(id); return next; });
          setReplayingIds(prev => { const next = new Set(prev); next.delete(id); return next; });
        }, 1500);
      }
    } catch (err) {
      console.error("Replay failed", err);
      setReplayingIds(prev => { const next = new Set(prev); next.delete(id); return next; });
    }
  };

  const enqueueJob = async (jobPayload, jobPriority, delay) => {
    const runAt = new Date(Date.now() + delay * 1000).toISOString();
    await fetch(API_BASE, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ payload: jobPayload, priority: jobPriority, runAt, maxAttempts: 3 })
    });
  };

  const handleNewJob = async (e) => {
    e.preventDefault();
    try {
      await enqueueJob(payload, parseInt(priority), parseInt(delaySecs || 0));
      setPayload('{"task": "send_email"}'); 
    } catch (err) {
      console.error("Failed to enqueue job", err);
    }
  };

  const handleSeedDemo = async () => {
    setSeeding(true);
    try {
      await enqueueJob('{"task": "resize_image"}', 5, 0);
      await enqueueJob('{"task": "charge_wallet", "user_id": "demo_user"}', 5, 0);
      await enqueueJob('{"task": "urgent_notification"}', 10, 0);
      await enqueueJob('{"task": "delayed_reminder"}', 5, 30);
      await enqueueJob('{"task": "process_video", "fail": true}', 5, 0);
    } catch (err) {
      console.error("Failed seeding demo", err);
    }
    setSeeding(false);
  };

  const calculateTimeRemaining = (lockedUntilIso) => {
    if (!lockedUntilIso) return 0;
    const diff = Math.floor((new Date(lockedUntilIso).getTime() - now) / 1000);
    return Math.max(0, diff);
  };

  const getTimerPillClass = (seconds) => {
    if (seconds > 10) return 'safe';
    if (seconds > 3) return 'warn';
    return 'danger';
  };

  // Previous poll value for deltas
  const previousPoll = history.length > 1 ? history[history.length - 2] : null;

  return (
    <div id="root">
      {/* Sidebar */}
      <aside className="sidebar">
        <div className="brand">
          <Database size={24} style={{ color: 'var(--accent-blue)' }} />
          Charon
        </div>
        <ul className="nav-menu">
          <li className="nav-item active">
            <LayoutDashboard size={18} /> Overview
          </li>
          <li className="nav-item disabled">
            <Layers size={18} /> All Jobs
          </li>
          <li className="nav-item disabled">
            <Trash2 size={18} /> Dead Letters
          </li>
          <li className="nav-item disabled" style={{ marginTop: 'auto' }}>
            <Settings size={18} /> Settings
          </li>
        </ul>
      </aside>

      {/* Main Content Area */}
      <main className="main-content">
        <header className="topbar">
          <div className="page-title">Queue Overview</div>
          <div className="topbar-right">
            <div className="clock">
              <Clock size={14} /> Last refreshed: {lastRefreshed}
            </div>
            <div className={`api-status ${apiConnected ? 'connected' : 'disconnected'}`}>
              <div className="status-dot"></div>
              {apiConnected ? 'API Connected' : 'Unreachable'}
            </div>
          </div>
        </header>

        <div className="content-wrapper">
          <div className="content-inner">

            {!apiConnected && (
              <div className="glass-card" style={{ marginBottom: '2rem', borderColor: 'var(--accent-red)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                  <AlertCircle size={32} style={{ color: 'var(--accent-red)' }} />
                  <div>
                    <h3 style={{ color: 'var(--text-primary)', marginBottom: '0.25rem' }}>Backend API Unreachable</h3>
                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem' }}>Ensure Spring Boot is running on port 8080. Dashboards will pause updating until connection is restored.</p>
                  </div>
                </div>
              </div>
            )}

            <div className="kpi-grid">
              <div className={`glass-card kpi-card pending ${pulsePending ? 'changed' : ''}`}>
                <div className="kpi-header">
                  <div className="kpi-title">Pending Jobs</div>
                  <Clock className="kpi-icon" size={20} />
                </div>
                <div className="kpi-body">
                  <div className="kpi-value">{apiConnected ? queueStatus.pendingCount : '-'}</div>
                  {apiConnected && <DeltaPill current={queueStatus.pendingCount} previous={previousPoll?.pending} />}
                </div>
                <div className="accent-bar"></div>
              </div>

              <div className={`glass-card kpi-card inflight ${pulseLeased ? 'changed' : ''}`}>
                <div className="kpi-header">
                  <div className="kpi-title">In-Flight</div>
                  <Zap className="kpi-icon" size={20} />
                </div>
                <div className="kpi-body">
                  <div className="kpi-value">{apiConnected ? queueStatus.leasedCount : '-'}</div>
                  {apiConnected && <DeltaPill current={queueStatus.leasedCount} previous={previousPoll?.inFlight} />}
                </div>
                <div className="accent-bar"></div>
              </div>

              <div className={`glass-card kpi-card dead ${pulseDead ? 'changed' : ''}`}>
                <div className="kpi-header">
                  <div className="kpi-title">Dead Letters</div>
                  <Skull className="kpi-icon" size={20} />
                </div>
                <div className="kpi-body">
                  <div className="kpi-value">{apiConnected ? queueStatus.deadCount : '-'}</div>
                  {apiConnected && <DeltaPill current={queueStatus.deadCount} previous={previousPoll?.dead} />}
                </div>
                <div className="accent-bar"></div>
              </div>
            </div>

            {/* Chart Section */}
            <div className="glass-card chart-container">
              <h2 className="chart-title">Queue Volume (Real-time)</h2>
              <ResponsiveContainer width="100%" height="80%">
                <AreaChart data={history} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                  <defs>
                    <linearGradient id="colorPending" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="var(--accent-amber)" stopOpacity={0.4}/>
                      <stop offset="95%" stopColor="var(--accent-amber)" stopOpacity={0}/>
                    </linearGradient>
                    <linearGradient id="colorInflight" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="var(--accent-blue)" stopOpacity={0.4}/>
                      <stop offset="95%" stopColor="var(--accent-blue)" stopOpacity={0}/>
                    </linearGradient>
                    <linearGradient id="colorDead" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="var(--accent-red)" stopOpacity={0.4}/>
                      <stop offset="95%" stopColor="var(--accent-red)" stopOpacity={0}/>
                    </linearGradient>
                  </defs>
                  <XAxis dataKey="time" stroke="var(--surface-border)" tick={{ fill: 'var(--text-muted)', fontSize: 11 }} />
                  <YAxis stroke="var(--surface-border)" tick={{ fill: 'var(--text-muted)', fontSize: 11 }} allowDecimals={false} />
                  <Tooltip content={<CustomTooltip />} />
                  <Area type="monotone" dataKey="pending" name="Pending" stroke="var(--accent-amber)" strokeWidth={2} fillOpacity={1} fill="url(#colorPending)" />
                  <Area type="monotone" dataKey="inFlight" name="In-Flight" stroke="var(--accent-blue)" strokeWidth={2} fillOpacity={1} fill="url(#colorInflight)" />
                  <Area type="monotone" dataKey="dead" name="Dead" stroke="var(--accent-red)" strokeWidth={2} fillOpacity={1} fill="url(#colorDead)" />
                </AreaChart>
              </ResponsiveContainer>
            </div>

            <div className="main-grid">
              {/* Left Column */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
                
                <div className="glass-card">
                  <h2 className="section-header">
                    <Plus size={20} className="header-icon" /> 
                    Enqueue Job
                  </h2>
                  <form onSubmit={handleNewJob} className="form-group" style={{ gap: '1.25rem' }}>
                    <div className="form-group">
                      <label>Payload (JSON)</label>
                      <input type="text" className="form-input" value={payload} onChange={(e) => setPayload(e.target.value)} required />
                    </div>
                    <div className="form-row">
                      <div className="form-group">
                        <label>Priority</label>
                        <input type="number" className="form-input" value={priority} onChange={(e) => setPriority(e.target.value)} required />
                      </div>
                      <div className="form-group">
                        <label>Delay (Secs)</label>
                        <input type="number" className="form-input" value={delaySecs} onChange={(e) => setDelaySecs(e.target.value)} min="0" />
                      </div>
                    </div>
                    <div className="form-row" style={{ marginTop: '0.5rem' }}>
                      <button type="submit" className="btn btn-primary" disabled={!apiConnected}>
                        <Send size={16} /> Submit Job
                      </button>
                      <button type="button" className="btn btn-secondary" onClick={handleSeedDemo} disabled={!apiConnected || seeding}>
                        {seeding ? <RefreshCw size={16} className="spinner" /> : <Play size={16} />}
                        Seed Demo
                      </button>
                    </div>
                  </form>
                </div>

                <div className="glass-card" style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
                  <h2 className="section-header">
                    <Server size={20} className="header-icon" /> 
                    Active Leased Jobs
                  </h2>
                  {(!apiConnected || queueStatus.leasedJobs.length === 0) ? (
                    <div className="empty-state" style={{ flex: 1 }}>
                      <CheckCircle className="empty-icon" size={40} />
                      <p>No jobs are currently being processed.</p>
                    </div>
                  ) : (
                    <div className="list-container">
                      {queueStatus.leasedJobs.map(job => {
                        const secs = calculateTimeRemaining(job.lockedUntil);
                        return (
                          <div className="list-item" key={job.jobId}>
                            <div className="item-main">
                              <span className="item-id">
                                #{job.jobId}
                                <span className="badge"><User size={10} /> {job.lockedBy.split('-')[0]}</span>
                              </span>
                            </div>
                            <div className={`timer-pill ${getTimerPillClass(secs)}`}>
                              <Clock size={14} /> {secs}s
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </div>

              {/* Right Column */}
              <div className="glass-card" style={{ display: 'flex', flexDirection: 'column' }}>
                <h2 className="section-header">
                  <Trash2 size={20} className="header-icon" /> 
                  Dead Letter Queue
                </h2>
                {(!apiConnected || deadLetters.length === 0) ? (
                  <div className="empty-state" style={{ flex: 1 }}>
                    <CheckCircle className="empty-icon" size={40} style={{ color: 'var(--accent-green)', opacity: 0.6 }} />
                    <p>Hooray! No dead letter jobs right now.</p>
                  </div>
                ) : (
                  <div className="list-container">
                    {deadLetters.map(job => {
                      const isReplaying = replayingIds.has(job.id);
                      const isSuccess = successIds.has(job.id);
                      return (
                        <div className="list-item" key={job.id}>
                          <div className="item-main">
                            <span className="item-id">#{job.id}</span>
                            <span className="item-meta" title={job.payload}>{job.payload}</span>
                            {job.lastError && (
                              <span className="item-error" title={job.lastError}>
                                <AlertCircle size={12} style={{ display: 'inline', marginRight: '4px', verticalAlign: 'text-bottom' }} />
                                {job.lastError}
                              </span>
                            )}
                          </div>
                          <button 
                            className={`btn ${isSuccess ? 'btn-secondary success-text' : 'btn-primary'}`}
                            onClick={() => handleReplay(job.id)}
                            disabled={isReplaying || isSuccess}
                          >
                            {isSuccess ? <><CheckCircle size={16} /> Queued!</> 
                            : isReplaying ? <><RefreshCw size={16} className="spinner" /> Replaying</> 
                            : <><RefreshCw size={16} /> Replay</>}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>

          </div>
        </div>
      </main>
    </div>
  );
}

export default App;
