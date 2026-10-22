import { defineConfig } from 'vite';

const server = {
  host: '127.0.0.1', port: 5173, strictPort: true,
  proxy: { '/api': { target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: true } },
};
export default defineConfig({
  server,
  preview: { ...server, port: 4173 },
  test: { environment: 'jsdom', setupFiles: './tests/setup.js', clearMocks: true },
});
