import React from 'react';

export default function GridStatus({ data }) {
  const status = data?.grid_status || 'UNKNOWN';
  const intensity = data?.carbon_intensity ?? 0;
  const zone = data?.zone || 'IN-NO';

  const dotClass =
    status === 'GREEN' ? 'green' :
    status === 'DIRTY' ? 'red' : 'amber';

  return (
    <div className="panel">
      <h2>Grid Status</h2>
      <div className="grid-indicator">
        <div className={`dot ${dotClass}`} />
        <div>
          <div className="grid-status-text">{status}</div>
          <div className="carbon-value">{intensity} gCO₂/kWh · zone {zone}</div>
        </div>
      </div>
    </div>
  );
}
