const statsSubtabs = document.querySelectorAll(".stats-subtab");
const statsYearControls = document.getElementById("stats-year-controls");
const statsYearSelect = document.getElementById("stats-year-select");
const statsContent = document.getElementById("stats-content");
const lastUpdatedEl = document.getElementById("last-updated");

let map;
let statsSubMode = "year";

// Color per transport mode, matching the main app's stats tab so a shared
// link looks consistent with the private view.
const ACTIVITY_COLORS = {
  WALKING: "#48bb78",
  ON_FOOT: "#48bb78",
  RUNNING: "#48bb78",
  CYCLING: "#38b2ac",
  IN_PASSENGER_VEHICLE: "#ed8936",
  IN_VEHICLE: "#ed8936",
  IN_BUS: "#ed8936",
  MOTORCYCLING: "#ed8936",
  IN_TRAIN: "#9f7aea",
  IN_SUBWAY: "#9f7aea",
  IN_TRAM: "#9f7aea",
  IN_FUNICULAR: "#9f7aea",
  FLYING: "#e53e3e",
  IN_FERRY: "#4299e1",
  SAILING: "#4299e1",
  SKIING: "#ecc94b",
};
const DEFAULT_ACTIVITY_COLOR = "#718096";

function activityColorExpression() {
  const expr = ["match", ["get", "activity_type"]];
  for (const [type, color] of Object.entries(ACTIVITY_COLORS)) {
    expr.push(type, color);
  }
  expr.push(DEFAULT_ACTIVITY_COLOR);
  return expr;
}

function fmtModeLabel(activityType) {
  return activityType
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function emptySourceData() {
  return { type: "FeatureCollection", features: [] };
}

function setSourceData(id, data) {
  const source = map.getSource(id);
  if (source) source.setData(data);
}

const TRIP_SOURCE = "trips";
const CITY_SOURCE = "cities";

function initMapLayers() {
  map.addSource(TRIP_SOURCE, { type: "geojson", data: emptySourceData() });
  map.addLayer({
    id: TRIP_SOURCE,
    type: "line",
    source: TRIP_SOURCE,
    layout: { "line-cap": "round", "line-join": "round" },
    paint: { "line-color": activityColorExpression(), "line-width": 2, "line-opacity": 0.85 },
  });

  map.addSource(CITY_SOURCE, { type: "geojson", data: emptySourceData() });
  map.addLayer({
    id: CITY_SOURCE,
    type: "circle",
    source: CITY_SOURCE,
    paint: {
      "circle-radius": 6,
      "circle-color": "#f6ad55",
      "circle-stroke-width": 1,
      "circle-stroke-color": "#1d2027",
    },
  });

  map.on("mouseenter", CITY_SOURCE, () => { map.getCanvas().style.cursor = "pointer"; });
  map.on("mouseleave", CITY_SOURCE, () => { map.getCanvas().style.cursor = ""; });
  map.on("click", CITY_SOURCE, (e) => {
    const p = e.features[0].properties;
    const label = [p.name, p.admin1, p.country_code].filter(Boolean).join(", ");
    new maplibregl.Popup({ closeButton: true }).setLngLat(e.lngLat).setHTML(`<strong>${label}</strong>`).addTo(map);
  });
}

function dimTrips(hoveredType) {
  map.setPaintProperty(
    TRIP_SOURCE,
    "line-opacity",
    hoveredType ? ["match", ["get", "activity_type"], hoveredType, 0.95, 0.12] : 0.85
  );
}

// ---- Stats controls ----

function populateStatsSelectors() {
  const currentYear = new Date().getFullYear();
  const years = Array.from({ length: 20 }, (_, i) => String(currentYear - i));
  statsYearSelect.innerHTML = years.map((y) => `<option value="${y}">${y}</option>`).join("");
  statsYearSelect.value = String(currentYear);
}

function statsDateRange() {
  if (statsSubMode === "all") return { from: null, to: null };
  const year = statsYearSelect.value;
  return { from: `${year}-01-01`, to: `${year}-12-31` };
}

function renderStats(data) {
  const modeRows = Object.entries(data.by_mode)
    .sort((a, b) => b[1] - a[1])
    .map(
      ([activityType, km]) => `
        <div class="stat-row stat-mode-row" data-activity-type="${activityType}">
          <span>${fmtModeLabel(activityType)}</span>
          <span>${km.toLocaleString()} km</span>
        </div>`
    )
    .join("");

  const countryNames = data.countries.map((c) => c.name).join(", ") || "None";

  statsContent.innerHTML = `
    <section class="stats-period">
      <div class="stat-row"><span>Distance travelled</span><span>${data.distance_km.toLocaleString()} km</span></div>
      <div class="stat-row stats-countries" id="stats-countries-toggle">
        <span>Countries visited</span><span>${data.countries.length}</span>
      </div>
      <div class="stats-country-list hidden" id="stats-country-list">${countryNames}</div>
      <div class="stat-row"><span>Cities visited</span><span>${data.cities.length}</span></div>
      ${modeRows ? `<div class="stat-modes">${modeRows}</div>` : ""}
    </section>
  `;

  document
    .getElementById("stats-countries-toggle")
    .addEventListener("click", () => document.getElementById("stats-country-list").classList.toggle("hidden"));

  statsContent.querySelectorAll(".stat-mode-row").forEach((row) => {
    row.addEventListener("mouseenter", () => dimTrips(row.dataset.activityType));
    row.addEventListener("mouseleave", () => dimTrips(null));
  });
}

async function loadStats() {
  statsContent.innerHTML = `<p class="hint">Crunching statistics&hellip;</p>`;
  const { from, to } = statsDateRange();
  const params = new URLSearchParams();
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  const res = await fetch(`/api/public-stats?${params.toString()}`);
  const data = await res.json();
  renderStats(data);

  setSourceData(TRIP_SOURCE, data.trips);
  const cityFeatures = data.cities.map((c) => ({
    type: "Feature",
    geometry: { type: "Point", coordinates: [c.lng, c.lat] },
    properties: c,
  }));
  setSourceData(CITY_SOURCE, { type: "FeatureCollection", features: cityFeatures });
}

statsSubtabs.forEach((t) =>
  t.addEventListener("click", () => {
    statsSubMode = t.dataset.substats;
    statsSubtabs.forEach((el) => el.classList.toggle("active", el === t));
    statsYearControls.classList.toggle("hidden", statsSubMode !== "year");
    loadStats();
  })
);
statsYearSelect.addEventListener("change", loadStats);

async function loadLastUpdated() {
  const res = await fetch("/api/last-updated");
  const data = await res.json();
  lastUpdatedEl.textContent = data.last_updated
    ? `Last updated: ${new Date(data.last_updated).toLocaleDateString(undefined, {
        year: "numeric",
        month: "short",
        day: "numeric",
      })}`
    : "No data uploaded yet";
}

(async function init() {
  populateStatsSelectors();
  loadLastUpdated();

  const config = await (await fetch("/api/config")).json();
  map = new maplibregl.Map({
    container: "map",
    style: config.tile_style_url,
    center: [0, 20],
    zoom: 1.5,
    dragRotate: false,
    pitchWithRotate: false,
    touchPitch: false,
  });
  map.touchZoomRotate.disableRotation();
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");

  map.on("load", () => {
    initMapLayers();
    loadStats();
  });
})();
