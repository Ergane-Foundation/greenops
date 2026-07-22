import math
import os
from datetime import datetime, timedelta, timezone
from typing import List, Dict

FORECAST_HORIZON_HOURS = int(os.getenv("FORECAST_HORIZON_HOURS", "24"))
FORECAST_RESOLUTION_MINUTES = int(os.getenv("FORECAST_RESOLUTION_MINUTES", "30"))

SIMULATED_BASELINE = int(os.getenv("SIMULATED_BASELINE_GCO2", "300"))
SIMULATED_AMPLITUDE = int(os.getenv("SIMULATED_AMPLITUDE_GCO2", "250"))
SIMULATED_PEAK_HOUR = int(os.getenv("SIMULATED_PEAK_HOUR", "19"))


def _floor_to_resolution(moment: datetime) -> datetime:
    minute = (moment.minute // FORECAST_RESOLUTION_MINUTES) * FORECAST_RESOLUTION_MINUTES
    return moment.replace(minute=minute, second=0, microsecond=0)


def simulated_intensity_at(moment: datetime) -> int:
    hours = moment.hour + moment.minute / 60.0
    offset = (hours - SIMULATED_PEAK_HOUR) / 24.0 * 2 * math.pi
    value = SIMULATED_BASELINE + SIMULATED_AMPLITUDE * math.cos(offset)
    return max(0, int(round(value)))


def simulated_forecast(zone: str, start: datetime = None) -> List[Dict]:
    start = _floor_to_resolution(start or datetime.now(timezone.utc))
    points = []
    steps = int(FORECAST_HORIZON_HOURS * 60 / FORECAST_RESOLUTION_MINUTES)
    for step in range(steps + 1):
        moment = start + timedelta(minutes=step * FORECAST_RESOLUTION_MINUTES)
        points.append({
            "datetime": moment.isoformat(),
            "carbon_intensity": simulated_intensity_at(moment),
        })
    return points


def shifted_forecast(zone: str, offset_gco2: int, start: datetime = None) -> List[Dict]:
    points = simulated_forecast(zone, start)
    for point in points:
        point["carbon_intensity"] = max(0, point["carbon_intensity"] + offset_gco2)
    return points


def parse_electricity_maps(payload: Dict) -> List[Dict]:
    points = []
    for entry in payload.get("forecast", []):
        intensity = entry.get("carbonIntensity")
        moment = entry.get("datetime")
        if intensity is None or moment is None:
            continue
        points.append({
            "datetime": moment,
            "carbon_intensity": int(intensity),
        })
    return points
