import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// @ts-expect-error process is a nodejs global
const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig(async () => ({
  plugins: [react()],

  clearScreen: false,
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
    // 启用 CSS 代码分割
    cssCodeSplit: true,
    // 压缩选项
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: false,
        drop_debugger: true,
      },
    },
    // chunk 大小警告阈值
    chunkSizeWarningLimit: 1000,
  },
}));
