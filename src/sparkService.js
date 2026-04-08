const WebSocket = require('ws');
const { buildAuthUrl } = require('./sparkAuth');

/**
 * 调用讯飞 Spark，通过 SSE 流式写回 Express res
 * @param {Array}    messages  [{role, content}, ...]
 * @param {object}   options   { temperature, maxTokens }
 * @param {object}   res       Express response（已设置 SSE headers）
 */
function sparkChat(messages, options, res) {
  const url = buildAuthUrl();
  const ws = new WebSocket(url);
  const timeout = setTimeout(() => {
    ws.terminate();
    res.write('event: error\ndata: Request timed out\n\n');
    res.end();
  }, Number(process.env.SPARK_TIMEOUT_MS) || 60000);

  ws.on('open', () => {
    const payload = {
      header: { app_id: process.env.SPARK_APP_ID },
      parameter: {
        chat: {
          domain: process.env.SPARK_DOMAIN,
          temperature: options.temperature ?? Number(process.env.SPARK_TEMPERATURE) ?? 0.5,
          top_k: Number(process.env.SPARK_TOP_K) || 4,
          max_tokens: options.maxTokens ?? Number(process.env.SPARK_MAX_TOKENS) ?? 2048,
        },
      },
      payload: {
        message: { text: messages },
      },
    };
    ws.send(JSON.stringify(payload));
  });

  ws.on('message', (data) => {
    try {
      const json = JSON.parse(data.toString());
      const code = json?.header?.code;

      if (code !== 0) {
        const msg = json?.header?.message || 'unknown error';
        res.write(`event: error\ndata: Spark error ${code}: ${msg}\n\n`);
        res.end();
        ws.terminate();
        clearTimeout(timeout);
        return;
      }

      const texts = json?.payload?.choices?.text ?? [];
      for (const t of texts) {
        if (t.content) {
          res.write(`data: ${JSON.stringify({ content: t.content })}\n\n`);
        }
      }

      // status=2 最后一帧
      if (json?.header?.status === 2) {
        res.write('event: done\ndata: [DONE]\n\n');
        res.end();
        ws.terminate();
        clearTimeout(timeout);
      }
    } catch (e) {
      res.write(`event: error\ndata: ${e.message}\n\n`);
      res.end();
      clearTimeout(timeout);
    }
  });

  ws.on('error', (err) => {
    clearTimeout(timeout);
    res.write(`event: error\ndata: ${err.message}\n\n`);
    res.end();
  });
}

module.exports = { sparkChat };
