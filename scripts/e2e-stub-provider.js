const http = require('http');

const port = Number(process.env.STUB_PORT || 19100);
const responseText = 'STUB_OK stream and sync paths are connected';

const server = http.createServer((request, response) => {
  if (!request.url.includes('/chat/completions')) {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ data: [{ id: 'stub-model', object: 'model' }] }));
    return;
  }

  let rawBody = '';
  request.on('data', chunk => { rawBody += chunk; });
  request.on('end', () => {
    if (!(request.headers.authorization || '').startsWith('Bearer sk-stub')) {
      response.writeHead(401, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: { message: 'bad key' } }));
      return;
    }

    const body = JSON.parse(rawBody || '{}');
    if (body.stream === true) {
      response.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive'
      });
      response.write(`data: ${JSON.stringify({
        id: 'chatcmpl-stub-stream', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000),
        model: 'stub-model', choices: [{ index: 0, delta: { role: 'assistant', content: responseText }, finish_reason: null }]
      })}\n\n`);
      setTimeout(() => {
        response.write(`data: ${JSON.stringify({
          id: 'chatcmpl-stub-stream', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000),
          model: 'stub-model', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
          usage: { prompt_tokens: 12, completion_tokens: 9, total_tokens: 21 }
        })}\n\n`);
        response.end('data: [DONE]\n\n');
      }, 25);
      return;
    }

    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({
      id: 'chatcmpl-stub-sync', object: 'chat.completion', created: Math.floor(Date.now() / 1000),
      model: 'stub-model',
      choices: [{ index: 0, message: { role: 'assistant', content: responseText }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 12, completion_tokens: 9, total_tokens: 21 }
    }));
  });
});

server.listen(port, '127.0.0.1', () => console.log(`stub provider on ${port}`));