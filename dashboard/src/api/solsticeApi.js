const BASE = '';

async function getJson(path) {
  const res = await fetch(BASE + path);
  if (!res.ok) throw new Error(`${path} returned ${res.status}`);
  return res.json();
}

export const api = {
  grid:       () => getJson('/api/grid'),
  cluster:    () => getJson('/api/cluster'),
  controller: () => getJson('/api/controller')
};
