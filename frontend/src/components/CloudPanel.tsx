import { useEffect, useState } from 'react';
import { getCloudCosts, getCloudInstances, type CostReport, type InstancesReport } from '../api/client';

/** Admin-only, read-only view of the AWS account. */
export default function CloudPanel() {
  const [instances, setInstances] = useState<InstancesReport | null>(null);
  const [costs, setCosts] = useState<CostReport | null>(null);
  const [loadedCosts, setLoadedCosts] = useState(false);

  useEffect(() => {
    getCloudInstances().then(setInstances).catch(() => setInstances(null));
  }, []);

  // Each Cost Explorer request is billed by AWS, so costs load only on demand.
  function loadCosts() {
    setLoadedCosts(true);
    getCloudCosts().then(setCosts).catch(() => setCosts(null));
  }

  return (
    <section className="card cloud">
      <div className="card-head">
        <h2>AWS (read-only)</h2>
        {!loadedCosts && <button className="secondary small-button" onClick={loadCosts}>Load month-to-date cost</button>}
      </div>
      {instances && !instances.enabled && <p className="muted">{instances.error}</p>}
      {instances?.enabled && instances.error && <p className="error">{instances.error}</p>}
      {instances?.enabled && !instances.error && instances.instances.length === 0 && <p className="muted">No EC2 instances in this region.</p>}
      {instances?.enabled && instances.instances.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr><th>Instance</th><th>Name</th><th>Type</th><th>State</th><th>Zone</th></tr>
            </thead>
            <tbody>
              {instances.instances.map((i) => (
                <tr key={i.id}>
                  <td><code>{i.id}</code></td>
                  <td>{i.name ?? '—'}</td>
                  <td>{i.type}</td>
                  <td><span className={`status ${i.state === 'running' ? 'succeeded' : 'pending'}`}>{i.state}</span></td>
                  <td>{i.availabilityZone ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {costs?.enabled && costs.total !== null && (
        <p>
          Month to date ({costs.period}): <strong>{costs.total} {costs.unit}</strong>
          {costs.topServices.length > 0 && <span className="muted"> · top: {costs.topServices.slice(0, 3).map((s) => `${s.service} ${s.amount}`).join(', ')}</span>}
        </p>
      )}
      {costs?.error && costs.enabled && <p className="error">{costs.error}</p>}
    </section>
  );
}
