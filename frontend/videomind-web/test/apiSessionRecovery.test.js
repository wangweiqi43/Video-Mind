import assert from 'node:assert/strict'
import test from 'node:test'
import { createHttpClient } from '../src/api.js'

function success(config, data) {
  return {
    config,
    data: { code: 0, data },
    headers: {},
    status: 200,
    statusText: 'OK'
  }
}

function forbidden(config) {
  const error = new Error('Request failed with status code 403')
  error.config = config
  error.response = { config, data: {}, headers: {}, status: 403, statusText: 'Forbidden' }
  return error
}

test('refreshes an expired session once and retries concurrent protected requests', async () => {
  let protectedCalls = 0
  let refreshCalls = 0
  const client = createHttpClient({
    adapter: async (config) => {
      if (config.url === '/auth/refresh') {
        refreshCalls += 1
        await new Promise((resolve) => setTimeout(resolve, 10))
        return success(config, { username: 'test-user' })
      }
      protectedCalls += 1
      if (protectedCalls <= 2) throw forbidden(config)
      return success(config, [{ id: protectedCalls }])
    }
  })

  const [first, second] = await Promise.all([
    client.get('/knowledge-bases'),
    client.get('/videos/list')
  ])

  assert.equal(refreshCalls, 1)
  assert.deepEqual([first[0].id, second[0].id].sort(), [3, 4])
})

test('does not loop when refreshing the session is rejected', async () => {
  let refreshCalls = 0
  const client = createHttpClient({
    adapter: async (config) => {
      if (config.url === '/auth/refresh') refreshCalls += 1
      throw forbidden(config)
    }
  })

  await assert.rejects(
    client.get('/knowledge-bases'),
    /登录状态已过期，请重新登录/
  )
  assert.equal(refreshCalls, 1)
})
