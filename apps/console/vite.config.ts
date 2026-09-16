import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// A same-origin development proxy keeps browser credentials away from CORS workarounds.
const target = process.env.EBLOCKER_DEV_TARGET;
if (target && !/^https?:\/\//.test(target))
    throw new Error('EBLOCKER_DEV_TARGET must be an HTTP(S) origin');
export default defineConfig(({ command }) => ({
    plugins: [
        react(),
        {
            name: 'development-html-policy',
            transformIndexHtml(html) {
                // Vite's development-only refresh preamble needs inline scripts. Production keeps the strict policy.
                return command === 'serve'
                    ? html.replace(/\s*<meta http-equiv="Content-Security-Policy"[^>]*>/, '')
                    : html;
            },
        },
    ],
    base: '/next/',
    server: {
        host: '127.0.0.1',
        proxy: target ? { '/api': { target, changeOrigin: true } } : undefined,
    },
    build: { sourcemap: false, target: 'es2022' },
    test: {
        environment: 'jsdom',
        setupFiles: ['./src/test/setup.ts'],
        include: ['src/**/*.test.{ts,tsx}'],
        restoreMocks: true,
    },
}));
