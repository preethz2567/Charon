import { useState, useEffect } from 'react';
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
      const res = await fetch(`${API_BASE}/dead-letters/${id}/replay`, {
        method: 'POST'
      });
      if (res.ok) {
        setSuccessIds(prev => new Set(prev).add(id));
        // Remove from list optimistically or wait for next poll
        setTimeout(() => {
          setSuccessIds(prev => {
            const next = new Set(prev);
            next.delete(id);
            return next;
          });
          setReplayingIds(prev => {
            const next = new Set(prev);
            next.delete(id);
            return next;
          });
        }, 1500);
      }
    } catch (err) {
      console.error("Replay failed", err);
      setReplayingIds(prev => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  };

  const calculateTimeRemaining = (lockedUntilIso) => {
    if (!lockedUntilIso) return 0;
    const lockedUntilDate = new Date(lockedUntilIso);
    const diffSeconds = Math.floor((lockedUntilDate.getTime() - now) / 1000);
    return Math.max(0, diffSeconds);
  };

  return (
    <div className="dashboard-container">
      <header className="header">
        <h1>Charon Dashboard</h1>
        <div className="api-status">
          <div className={`status-dot ${apiConnected ? 'connected' : 'disconnected'}`}></div>
          {apiConnected ? 'API Connected' : 'API Unreachable'}
        </div>
      </header>

      {!apiConnected && (
        <div className="empty-state" style={{ marginBottom: '2rem', borderColor: 'var(--error-color)', color: 'var(--error-color)' }}>
          <p>Backend API is currently unreachable. Make sure Spring Boot is running on port 8080.</p>
        </div>
      )}

      <div className="kpi-grid">
        <div className="kpi-card">
          <div className="kpi-title">Pending Jobs</div>
          <div className="kpi-value pending">{apiConnected ? queueStatus.pendingCount : '-'}</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-title">In-Flight</div>
          <div className="kpi-value inflight">{apiConnected ? queueStatus.leasedCount : '-'}</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-title">Dead Letters</div>
          <div className="kpi-value dead">{apiConnected ? queueStatus.deadCount : '-'}</div>
        </div>
      </div>

      <div className="sections-grid">
        {/* Leased Jobs Section */}
        <div className="section">
          <h2 className="section-header">Active Leased Jobs</h2>
          {(!apiConnected || queueStatus.leasedJobs.length === 0) ? (
            <div className="empty-state">
              <p>No jobs are currently being processed.</p>
            </div>
          ) : (
            <div className="item-list">
              {queueStatus.leasedJobs.map(job => (
                <div className="job-card" key={job.jobId}>
                  <div className="job-info">
                    <span className="job-id">Job #{job.jobId}</span>
                    <span className="job-meta">Worker: {job.lockedBy.split('-')[0]}...</span>
                  </div>
                  <div className="timer">
                    {calculateTimeRemaining(job.lockedUntil)}s left
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Dead Letters Section */}
        <div className="section">
          <h2 className="section-header">Dead Letter Queue</h2>
          {(!apiConnected || deadLetters.length === 0) ? (
            <div className="empty-state">
              <p>Hooray! No dead letter jobs right now.</p>
            </div>
          ) : (
            <div className="item-list">
              {deadLetters.map(job => {
                const isReplaying = replayingIds.has(job.id);
                const isSuccess = successIds.has(job.id);

                return (
                  <div className="job-card" key={job.id}>
                    <div className="job-info" style={{ maxWidth: '70%' }}>
                      <span className="job-id">Job #{job.id}</span>
                      <span className="job-meta">Payload: {job.payload}</span>
                      {job.lastError && (
                        <div className="job-error">{job.lastError}</div>
                      )}
                    </div>
                    <button 
                      onClick={() => handleReplay(job.id)}
                      disabled={isReplaying || isSuccess}
                      className={isSuccess ? 'success' : ''}
                    >
                      {isSuccess ? 'Queued!' : (isReplaying ? 'Replaying...' : 'Replay')}
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
