/**
 * 生成讯飞 WebSocket 鉴权 URL（Web Crypto API）
 */
async function buildAuthUrl(env) {
  const host = env.SPARK_HOST;
  const path = env.SPARK_PATH;
  const date = new Date().toUTCString();

  const signatureOrigin = `host: ${host}\ndate: ${date}\nGET ${path} HTTP/1.1`;

  // HMAC-SHA256
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(env.SPARK_API_SECRET),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const signatureBytes = await crypto.subtle.sign('HMAC', key, encoder.encode(signatureOrigin));
  const signature = btoa(String.fromCharCode(...new Uint8Array(signatureBytes)));

  // Authorization
  const authorizationOrigin =
    `api_key="${env.SPARK_API_KEY}", algorithm="hmac-sha256", ` +
    `headers="host date request-line", signature="${signature}"`;
  const authorization = btoa(authorizationOrigin);

  const params = new URLSearchParams({ authorization, date, host });
  return `wss://${host}${path}?${params.toString()}`;
}

/**
 * 处理 /api/chat 请求
 */
async function handleChat(request, env) {
  try {
    const { messages, temperature, maxTokens } = await request.json();

    if (!Array.isArray(messages) || messages.length === 0) {
      return new Response(JSON.stringify({ error: 'messages is required' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' },
      });
    }

    const url = await buildAuthUrl(env);
    const ws = new WebSocket(url);

    const { readable, writable } = new TransformStream();
    const writer = writable.getWriter();
    const encoder = new TextEncoder();

    ws.addEventListener('open', () => {
      const payload = {
        header: { app_id: env.SPARK_APP_ID },
        parameter: {
          chat: {
            domain: env.SPARK_DOMAIN,
            temperature: temperature ?? parseFloat(env.SPARK_TEMPERATURE) ?? 0.5,
            top_k: parseInt(env.SPARK_TOP_K) || 4,
            max_tokens: maxTokens ?? parseInt(env.SPARK_MAX_TOKENS) ?? 2048,
          },
        },
        payload: { message: { text: messages } },
      };
      ws.send(JSON.stringify(payload));
    });

    ws.addEventListener('message', async (event) => {
      try {
        const json = JSON.parse(event.data);
        const code = json?.header?.code;

        if (code !== 0) {
          const msg = json?.header?.message || 'unknown error';
          await writer.write(encoder.encode(`event: error\ndata: Spark error ${code}: ${msg}\n\n`));
          await writer.close();
          ws.close();
          return;
        }

        const texts = json?.payload?.choices?.text ?? [];
        for (const t of texts) {
          if (t.content) {
            await writer.write(encoder.encode(`data: ${t.content}\n\n`));
          }
        }

        if (json?.header?.status === 2) {
          await writer.write(encoder.encode('event: done\ndata: [DONE]\n\n'));
          await writer.close();
          ws.close();
        }
      } catch (e) {
        await writer.write(encoder.encode(`event: error\ndata: ${e.message}\n\n`));
        await writer.close();
      }
    });

    ws.addEventListener('error', async (err) => {
      await writer.write(encoder.encode(`event: error\ndata: ${err.message}\n\n`));
      await writer.close();
    });

    return new Response(readable, {
      headers: {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
      },
    });
  } catch (e) {
    return new Response(JSON.stringify({ error: e.message }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' },
    });
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/api/chat' && request.method === 'POST') {
      return handleChat(request, env);
    }

    return new Response('cheney-chat is running', { status: 200 });
  },
};
