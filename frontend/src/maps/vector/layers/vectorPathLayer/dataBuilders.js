import { createFeatureCollection, toFiniteNumber } from '@/maps/vector/utils/maplibreLayerUtils'
import {
  HIGHLIGHTED_TRIP_SPEED_BAND_COLORS
} from '@/maps/shared/highlightedTripSpeedBands'
import {
  buildHighlightedTripData,
  createEmptyHighlightedTripData
} from '@/maps/shared/highlightedTripData'
import { densifyLongHops } from '@/maps/shared/greatCircle' // FORK

const createEmptyHighlightedData = () => ({
  lineCollection: createFeatureCollection([]),
  ...createEmptyHighlightedTripData(),
  fullLineCoordinates: []
})

export const normalizePathCoordinates = (pathGroup) => {
  if (!Array.isArray(pathGroup)) {
    return []
  }

  return pathGroup
    .map((point) => {
      const latitude = toFiniteNumber(point?.latitude)
      const longitude = toFiniteNumber(point?.longitude)

      if (latitude === null || longitude === null) {
        return null
      }

      return [longitude, latitude]
    })
    .filter(Boolean)
}

export const buildPathCollection = (pathData) => {
  const features = []

  ;(Array.isArray(pathData) ? pathData : []).forEach((pathGroup, pathIndex) => {
    const rawCoordinates = normalizePathCoordinates(pathGroup)
    if (rawCoordinates.length < 2) {
      return
    }

    // FORK: Google Timeline records a flight as a single start/end pair, so
    // upstream drew a straight lat/lng line - which is not the route taken, and
    // which sweeps the wrong way round the world when it crosses the
    // antimeridian. Long hops become great-circle arcs; short segments are left
    // exactly as they were. `pathRaw` below still carries the original points,
    // so hover and inspect report real GPS fixes, not interpolated ones.
    const coordinates = densifyLongHops(rawCoordinates)

    features.push({
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates
      },
      properties: {
        pathIndex,
        pathRaw: JSON.stringify(pathGroup || [])
      }
    })
  })

  return createFeatureCollection(features)
}

export const buildHighlightedData = ({
  highlightedTrip,
  pathData,
  allowPathDataFallback = false
}) => {
  if (!highlightedTrip || highlightedTrip.type !== 'trip') {
    return createEmptyHighlightedData()
  }

  const highlightedData = buildHighlightedTripData({
    highlightedTrip,
    pathData,
    allowPathDataFallback
  })
  if (!highlightedData.renderedTripPoints.length) {
    return createEmptyHighlightedData()
  }

  const lineCollection = createFeatureCollection(highlightedData.highlightedSegments.segments.map((segment) => ({
    type: 'Feature',
    geometry: {
      type: 'LineString',
      coordinates: segment.coordinates
    },
    properties: {
      speedBand: segment.speedBand,
      tripRaw: JSON.stringify(highlightedTrip || {})
    }
  })))

  return {
    ...highlightedData,
    lineCollection,
    fullLineCoordinates: highlightedData.lineCoordinates
  }
}

export const resolvePopupAnchorCoordinate = (lineCoordinates) => {
  if (!Array.isArray(lineCoordinates) || lineCoordinates.length === 0) {
    return null
  }

  return lineCoordinates[Math.floor(lineCoordinates.length / 2)] || lineCoordinates[0]
}

export const highlightedLineColorExpression = [
  'match',
  ['get', 'speedBand'],
  'red', HIGHLIGHTED_TRIP_SPEED_BAND_COLORS.red,
  'yellow', HIGHLIGHTED_TRIP_SPEED_BAND_COLORS.yellow,
  'green', HIGHLIGHTED_TRIP_SPEED_BAND_COLORS.green,
  'unknown', HIGHLIGHTED_TRIP_SPEED_BAND_COLORS.unknown,
  HIGHLIGHTED_TRIP_SPEED_BAND_COLORS.unknown
]
