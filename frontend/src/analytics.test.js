import test from 'node:test'
import assert from 'node:assert/strict'

import {
  activityStatus,
  formatAccessReuseCount,
  formatUtcTimestamp,
} from './analytics.js'

test('formats the stored access and reuse count', () => {
  assert.equal(formatAccessReuseCount(1234), '1,234')
  assert.equal(formatAccessReuseCount(-1), '0')
})

test('formats a valid activity timestamp explicitly as UTC', () => {
  const formatted = formatUtcTimestamp('2026-08-20T10:37:31Z')

  assert.match(formatted, /Aug 20, 2026/)
  assert.match(formatted, /UTC$/)
  assert.equal(formatUtcTimestamp('not-a-date'), 'Unavailable')
})

test('describes zero activity without treating creation time as an access', () => {
  assert.equal(activityStatus(false), 'No counted post-creation activity yet.')
  assert.match(activityStatus(true), /successful redirects and repeated URL submissions/)
})
