import React from 'react';

function classify(action) {
  if (!action) return '';
  if (action.includes('FAILED') || action.includes('ERROR')) return 'error';
  if (action.includes('SAVEPOINT') || action.includes('SCALE')) return 'success';
  return '';
}

function fmtTs(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleTimeString();
}

export default function EventLog({ controller }) {
  const status = controller?.status || {};
  const events = [];

  if (status.lastReconciledAt) {
    events.push({
      ts: status.lastReconciledAt,
      msg: `Reconciled · grid=${status.gridStatus || '?'} · carbon=${status.carbonIntensity ?? '?'}`,
      cls: ''
    });
  }
  if (status.lastSavepointAt) {
    events.push({
      ts: status.lastSavepointAt,
      msg: `Savepoint ${status.savepointPhase} → ${status.lastSavepointPath}`,
      cls: 'success'
    });
  }
  if (status.lastAction) {
    events.push({
      ts: status.lastReconciledAt,
      msg: `Action: ${status.lastAction}`,
      cls: classify(status.lastAction)
    });
  }
  if (status.lastError) {
    events.push({
      ts: status.lastReconciledAt,
      msg: `Error: ${status.lastError}`,
      cls: 'error'
    });
  }

  return (
    <div className="panel wide">
      <h2>Event Log</h2>
      {events.length === 0 ? (
        <div className="carbon-value">No events yet — waiting for first reconciliation.</div>
      ) : (
        <div className="event-log">
          {events.map((e, i) => (
            <div key={i} className={`event ${e.cls}`}>
              <div className="ts">{fmtTs(e.ts)}</div>
              <div className="msg">{e.msg}</div>
            </div>
          ))}
        </div>
      )}
      {status.savepointPhase && (
        <div className="meta">
          <span><strong>Phase</strong>: {status.savepointPhase}</span>
          {status.lastSavepointPath && <span><strong>Path</strong>: {status.lastSavepointPath}</span>}
        </div>
      )}
    </div>
  );
}
