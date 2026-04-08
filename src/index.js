require('dotenv').config();
const express = require('express');
const { sparkChat } = require('./sparkService');

const app = express();
app.use(express.json());

/**
 * POST /api/chat
 * Body: { messages: [{role, content}], temperature?, maxTokens? }
 * Response: text/event-stream (SSE)
 */
app.post('/api/chat', (req, res) => {
  const { messages, temperature, maxTokens } = req.body;

  if (!Array.isArray(messages) || messages.length === 0) {
    return res.status(400).json({ error: 'messages is required' });
  }

  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.flushHeaders();

  sparkChat(messages, { temperature, maxTokens }, res);
});

const PORT = process.env.PORT || 8080;
app.listen(PORT, () => {
  console.log(`cheney-chat running on http://localhost:${PORT}`);
});
