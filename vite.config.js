import { defineConfig } from 'vite';
import path from 'path';

export default defineConfig({
  root: './src',
  base: './',
  build: {
    outDir: '../app/src/main/assets/web',
    emptyOutDir: true,
    target: 'es2020',
    assetsDir: 'assets',
    minify: 'esbuild',
    rollupOptions: {
      output: {
        format: 'iife',
        entryFileNames: 'assets/[name].js',
        chunkFileNames: 'assets/[name].js',
        assetFileNames: 'assets/[name].[ext]'
      }
    }
  }
});
