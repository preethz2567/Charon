import { useState, useEffect, useRef } from 'react';
import { 
  Clock, Zap, Skull, Server, AlertCircle, 
  Send, Plus, RefreshCw, CheckCircle, Database,
  User, Play, Trash2
} from 'lucide-react';
import './index.css';

const API_BASE = 'http://localhost:8080/jobs';

function App() {
  const [queueStatus, setQueueStatus] = useState({
    pendingCount: 0,
    leasedCount: 0,
    deadCount: 0,
    leasedJobs: []
  });
  const [deadLetters, setDeadLetters] = useState([]);
  const [apiConnected, setApiConnected] = useState(true);
  const [now, setNow] = useState(Date.now());
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

  // Fast timer for live lease countdown
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
          setPulsePending(true);
          setTimeout(() => setPulsePending(false), 1000);
          prevPending.current = statusData.pendingCount;
        }
        if (statusData.leasedCount !== prevLeased.current) {
          setPulseLeased(true);
          setTimeout(() => setPulseLeased(false), 1000);
          prevLeased.current = statusData.leasedCount;
        }
        if (statusData.deadCount !== prevDead.current) {
          setPulseDead(true);
          setTimeout(() => setPulseDead(false), 1000);
          prevDead.current = statusData.deadCount;
        }

        setQueueStatus(statusData);
        setDeadLetters(deadData);
        setApiConnected(true);
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
      body: JSON.stringify({
        payload: jobPayload,
        priority: jobPriority,
        runAt: runAt,
        maxAttempts: 3
      })
    });
  };

  const handleNewJob = async (e) => {
    e.preventDefault();
    try {
      await enqueueJob(payload, parseInt(priority), parseInt(delaySecs || 0));
      setPayload('{"task": "send_email"}'); // reset
    } catch (err) {
      console.error("Failed to enqueue job", err);
    }
  };

  const handleSeedDemo = async () => {
    setSeeding(true);
    try {
      // 1. Normal jobs
      await enqueueJob('{"task": "resize_image"}', 5, 0);
      await enqueueJob('{"task": "charge_wallet", "user_id": "demo_user"}', 5, 0);
      // 2. High priority job
      await enqueueJob('{"task": "urgent_notification"}', 10, 0);
      // 3. Delayed job
      await enqueueJob('{"task": "delayed_reminder"}', 5, 30);
      // 4. Malformed job that throws exception to test dead-letter
      await enqueueJob('{"task": "process_video", "fail": true}', 5, 0);
    } catch (err) {
      console.error("Failed seeding demo", err);
    }
    setSeeding(false);
  };

  const calculateTimeRemaining = (lockedUntilIso) => {
    if (!lockedUntilIso) return 0;
    const lockedUntilDate = new Date(lockedUntilIso);
    const diffSeconds = Math.floor((lockedUntilDate.getTime() - now) / 1000);
    return Math.max(0, diffSeconds);
  };

  const getTimerPillClass = (seconds) => {
    if (seconds > 10) return 'safe';
    if (seconds > 3) return 'warn';
    return 'danger';
  };

  return (
    <div className="dashboard-container">
      <header className="header">
        <h1>
          <Database className="header-icon" size={32} />
          Charon Console
        </h1>
        <div className={`api-status ${apiConnected ? 'connected' : 'disconnected'}`}>
          <div className={`status-dot ${apiConnected ? 'connected' : 'disconnected'}`}></div>
          {apiConnected ? 'API Connected' : 'API Unreachable'}
        </div>
      </header>

      {!apiConnected && (
        <div className="empty-state" style={{ marginBottom: '2.5rem', borderColor: 'var(--accent-red)' }}>
          <AlertCircle size={48} className="empty-icon" style={{ color: 'var(--accent-red)', opacity: 0.8 }} />
          <p style={{ color: 'var(--text-primary)', fontSize: '1rem' }}>Backend API is currently unreachable.</p>
          <p style={{ marginTop: '0.5rem', color: 'var(--text-secondary)' }}>Ensure Spring Boot is running on port 8080.</p>
        </div>
      )}

      <div className="kpi-grid">
        <div className={`glass-card kpi-card pending ${pulsePending ? 'changed' : ''}`}>
          <div className="kpi-header">
            <div className="kpi-title">Pending Jobs</div>
            <Clock className="kpi-icon" size={20} />
          </div>
          <div className="kpi-value">{apiConnected ? queueStatus.pendingCount : '-'}</div>
          <div className="accent-bar"></div>
        </div>

        <div className={`glass-card kpi-card inflight ${pulseLeased ? 'changed' : ''}`}>
          <div className="kpi-header">
            <div className="kpi-title">In-Flight</div>
            <Zap className="kpi-icon" size={20} />
          </div>
          <div className="kpi-value">{apiConnected ? queueStatus.leasedCount : '-'}</div>
          <div className="accent-bar"></div>
        </div>

        <div className={`glass-card kpi-card dead ${pulseDead ? 'changed' : ''}`}>
          <div className="kpi-header">
            <div className="kpi-title">Dead Letters</div>
            <Skull className="kpi-icon" size={20} />
          </div>
          <div className="kpi-value">{apiConnected ? queueStatus.deadCount : '-'}</div>
          <div className="accent-bar"></div>
        </div>
      </div>

      <div className="main-grid">
        {/* Left Column: Management & Active Jobs */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
          
          <div className="glass-card form-card">
            <h2 className="section-header">
              <Plus size={20} className="header-icon" /> 
              Enqueue Job
            </h2>
            <form onSubmit={handleNewJob} className="form-group" style={{ gap: '1.25rem' }}>
              <div className="form-group">
                <label>Payload (JSON)</label>
                <input 
                  type="text" 
                  className="form-input" 
                  value={payload} 
                  onChange={(e) => setPayload(e.target.value)} 
                  required 
                />
              </div>
              <div className="form-row">
                <div className="form-group">
                  <label>Priority</label>
                  <input 
                    type="number" 
                    className="form-input" 
                    value={priority} 
                    onChange={(e) => setPriority(e.target.value)} 
                    required 
                  />
                </div>
                <div className="form-group">
                  <label>Delay (Secs)</label>
                  <input 
                    type="number" 
                    className="form-input" 
                    value={delaySecs} 
                    onChange={(e) => setDelaySecs(e.target.value)} 
                    min="0"
                  />
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

          <div className="glass-card" style={{ padding: '1.5rem', flex: 1 }}>
            <h2 className="section-header">
              <Server size={20} className="header-icon" /> 
              Active Leased Jobs
            </h2>
            {(!apiConnected || queueStatus.leasedJobs.length === 0) ? (
              <div className="empty-state">
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
                          <span className="badge">
                            <User size={10} /> {job.lockedBy.split('-')[0]}
                          </span>
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

        {/* Right Column: Dead Letter Queue */}
        <div className="glass-card" style={{ padding: '1.5rem' }}>
          <h2 className="section-header">
            <Trash2 size={20} className="header-icon" /> 
            Dead Letter Queue
          </h2>
          {(!apiConnected || deadLetters.length === 0) ? (
            <div className="empty-state">
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
                      style={{ padding: '0.5rem 0.75rem' }}
                      onClick={() => handleReplay(job.id)}
                      disabled={isReplaying || isSuccess}
                    >
                      {isSuccess ? (
                        <><CheckCircle size={16} /> Queued!</>
                      ) : isReplaying ? (
                        <><RefreshCw size={16} className="spinner" /> Replaying</>
                      ) : (
                        <><RefreshCw size={16} /> Replay</>
                      )}
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default App;
