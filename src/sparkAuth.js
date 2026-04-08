const crypto = require('crypto');

/**
 * 生成讯飞 WebSocket 鉴权 URL
 */
function buildAuthUrl() {
  const host = process.env.SPARK_HOST;
  const path = process.env.SPARK_PATH;

  const date = new Date().toUTCString();

  const signatureOrigin = `host: ${host}\ndate: ${date}\nGET ${path} HTTP/1.1`;
  const signature = crypto
    .createHmac('sha256', process.env.SPARK_API_SECRET)
    .update(signatureOrigin)
    .digest('base64');

  const authorizationOrigin =
    `api_key="${process.env.SPARK_API_KEY}", algorithm="hmac-sha256", ` +
    `headers="host date request-line", signature="${signature}"`;
  const authorization = Buffer.from(authorizationOrigin).toString('base64');

  const params = new URLSearchParams({ authorization, date, host });
  return `wss://${host}${path}?${params.toString()}`;
}

module.exports = { buildAuthUrl };
