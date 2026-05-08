import React from 'react';

export default function ReplicaCount({ data }) {
  const jm = data?.jobmanager_replicas ?? 0;
  const tm = data?.taskmanager_pods ?? 0;
  const total = jm + tm;

  return (
    <div className="panel">
      <h2>Cluster Status</h2>
      <div className={`replica-count ${total === 0 ? 'zero' : 'nonzero'}`}>{total}</div>
      <div className="replica-label">running Flink pods</div>
      <div className="meta">
        <span><strong>JobManager</strong>: {jm}</span>
        <span><strong>TaskManager</strong>: {tm}</span>
      </div>
    </div>
  );
}
