import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// @ts-expect-error process is a nodejs global
const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig(async () => ({
  plugins: [react()],

  clearScreen: false,

  // 预打包大依赖，避免 dev 模式每次冷启动重新编译
  optimizeDeps: {
    include: [
      'react',
      'react-dom',
      'react-markdown',
      'react-syntax-highlighter',
      'react-syntax-highlighter/dist/esm/styles/prism',
      'remark-breaks',
      '@monaco-editor/react',
      '@xterm/xterm',
      '@xterm/addon-fit',
      'dexie',
    ],
  },

  server: {
    port: 1420,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: "ws",
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      ignored: ["**/src-tauri/**"],
    },
    proxy: {
      '/cli': {
        target: 'http://127.0.0.1:4808',
        changeOrigin: true,
        secure: false
      }
    }
  },

  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'monaco-editor': ['@monaco-editor/react'],
          'xterm': ['@xterm/xterm', '@xterm/addon-fit'],
          'syntax-highlighter': ['react-syntax-highlighter', 'react-markdown', 'remark-breaks'],
          'vendor-react': ['react', 'react-dom'],
        },
      },
    },
    cssCodeSplit: true,
    minify: 'esbuild',
    chunkSizeWarningLimit: 1000,
  },
}));
