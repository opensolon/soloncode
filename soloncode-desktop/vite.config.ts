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
        manualChunks(id) {
          const normalized = id.replace(/\\/g, '/');
          if (normalized.includes('/node_modules/@monaco-editor/') || normalized.includes('/node_modules/monaco-editor/')) {
            return 'monaco-editor';
          }
          if (normalized.includes('/node_modules/@xterm/')) {
            return 'xterm';
          }
          if (
            normalized.includes('/node_modules/react-syntax-highlighter/') ||
            normalized.includes('/node_modules/refractor/') ||
            normalized.includes('/node_modules/prismjs/')
          ) {
            return 'syntax-highlighter';
          }
          if (
            normalized.includes('/node_modules/react-markdown/') ||
            normalized.includes('/node_modules/remark-') ||
            normalized.includes('/node_modules/rehype-') ||
            normalized.includes('/node_modules/mdast-') ||
            normalized.includes('/node_modules/hast-') ||
            normalized.includes('/node_modules/micromark') ||
            normalized.includes('/node_modules/unified/') ||
            normalized.includes('/node_modules/unist-')
          ) {
            return 'markdown';
          }
          if (normalized.includes('/node_modules/react/') || normalized.includes('/node_modules/react-dom/')) {
            return 'vendor-react';
          }
        },
      },
    },
    cssCodeSplit: true,
    minify: 'esbuild',
    chunkSizeWarningLimit: 1000,
  },
}));
