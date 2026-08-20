export function formatAccessReuseCount(value) {
  const count = Number.isFinite(value) && value >= 0 ? value : 0
  return new Intl.NumberFormat('en-US').format(count)
}

export function formatUtcTimestamp(value) {
  if (!value) return 'Unavailable'

  const timestamp = new Date(value)
  if (Number.isNaN(timestamp.getTime())) return 'Unavailable'

  const formatted = new Intl.DateTimeFormat('en-US', {
    dateStyle: 'medium',
    timeStyle: 'medium',
    timeZone: 'UTC',
  }).format(timestamp)

  return `${formatted} UTC`
}

export function activityStatus(hasRecordedActivity) {
  return hasRecordedActivity
    ? 'Includes successful redirects and repeated URL submissions.'
    : 'No counted post-creation activity yet.'
}
