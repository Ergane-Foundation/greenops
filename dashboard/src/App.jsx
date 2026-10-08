import React, { useEffect, useState } from 'react';
import GridStatus from './components/GridStatus.jsx';
import ReplicaCount from './components/ReplicaCount.jsx';
import EventLog from './components/EventLog.jsx';
import { api } from './api/solsticeApi.js';

const POLL_MS = 5000;

export default function App() {
  const [grid, setGrid] = useState(null);
  const [cluster, setCluster] = useState(null);
  const [controller, setController] = useState(null);
  const [error, setError] = useState(null);
  const [updatedAt, setUpdatedAt] = useState(null);

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      try {
        const [g, c, ctrl] = await Promise.all([
          api.grid().catch(() => null),
          api.cluster().catch(() => null),
          api.controller().catch(() => null)
        ]);
        if (cancelled) return;
        setGrid(g);
        setCluster(c);
        setController(ctrl);
        setError(null);
        setUpdatedAt(new Date());
      } catch (e) {
        if (!cancelled) setError(e.message);
      }
    }

    poll();
    const id = setInterval(poll, POLL_MS);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  return (
    <div className="app">
      <div className="header">
        <h1>Solstice</h1>
        <div className="updated">
          {updatedAt ? `updated ${updatedAt.toLocaleTimeString()}` : 'loading…'}
        </div>
      </div>

      {error && <div className="error-banner">backend error: {error}</div>}

      <div className="grid">
        <GridStatus data={grid} />
        <ReplicaCount data={cluster} />
        <EventLog controller={controller} />
      </div>
    </div>
  );
}
