// SPDX-License-Identifier: EUPL-1.2
export type ErrorCode =
    | 'network'
    | 'timeout'
    | 'cancelled'
    | 'invalidResponse'
    | 'sessionExpired'
    | 'forbidden'
    | 'notFound'
    | 'server'
    | 'credentials'
    | 'wait'
    | 'validation'
    | 'conflict';
export class ApiError extends Error {
    constructor(readonly code: ErrorCode) {
        super(code);
        this.name = 'ApiError';
    }
}
export function errorCode(error: unknown): ErrorCode {
    return error instanceof ApiError ? error.code : 'network';
}
export type Transport = typeof fetch;
export type Download = { blob: Blob; filename: string };

function downloadName(disposition: string | null, fallback: string): string {
    const encoded = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
    const ordinary = disposition?.match(/filename="([^"]+)"|filename=([^;]+)/i);
    let name = ordinary?.[1] ?? ordinary?.[2] ?? fallback;
    if (encoded) {
        try {
            name = decodeURIComponent(encoded.trim());
        } catch {
            /* Use the ordinary safe name. */
        }
    }
    name =
        name
            .split(/[\\/]/)
            .pop()
            ?.replace(/[\u0000-\u001f\u007f]/g, '')
            .trim() ?? '';
    return !name || name === '.' || name === '..' ? fallback : name;
}

export async function request(
    transport: Transport,
    path: string,
    options: {
        token?: string;
        cookieSession?: boolean;
        csrfToken?: string;
        password?: string;
        signal?: AbortSignal;
        method?: 'GET' | 'PATCH' | 'POST' | 'PUT' | 'DELETE';
        acceptEmpty?: boolean;
        apiRoot?: '/api/adminconsole' | '/api';
        body?: unknown;
        rawBody?: Blob;
        downloadFilename?: string;
        timeoutMs?: number;
    } = {},
): Promise<unknown> {
    if (
        !path.startsWith('/') ||
        path.startsWith('//') ||
        /[\\#]/.test(path) ||
        path.split('/').includes('..')
    ) {
        throw new ApiError('validation');
    }
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(), options.timeoutMs ?? 12_000);
    const signal = options.signal
        ? AbortSignal.any([options.signal, timeout.signal])
        : timeout.signal;
    try {
        if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
        const headers = new Headers({
            Accept: options.downloadFilename ? 'application/octet-stream' : 'application/json',
        });
        if (options.token) headers.set('Authorization', `Bearer ${options.token}`);
        if (options.cookieSession) headers.set('X-Eblocker-Console', '1');
        if (options.csrfToken) headers.set('X-CSRF-Token', options.csrfToken);
        if (options.password !== undefined || options.body !== undefined)
            headers.set('Content-Type', 'application/json');
        if (options.rawBody) headers.set('Content-Type', 'application/octet-stream');
        const response = await transport(`${options.apiRoot ?? '/api/adminconsole'}${path}`, {
            method: options.password === undefined ? (options.method ?? 'GET') : 'POST',
            headers,
            credentials: 'same-origin',
            cache: 'no-store',
            redirect: 'error',
            signal,
            body:
                options.rawBody ??
                (options.password === undefined
                    ? options.body === undefined
                        ? undefined
                        : JSON.stringify(options.body)
                    : JSON.stringify({ currentPassword: options.password })),
        });
        if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
        if (response.ok && options.downloadFilename) {
            // Both binary endpoints explicitly return application/octet-stream.
            // A proxy/login/error document with HTTP 200 must never become a backup.
            const contentType = response.headers
                .get('Content-Type')
                ?.split(';', 1)[0]
                .trim()
                .toLowerCase();
            if (contentType !== 'application/octet-stream') throw new ApiError('invalidResponse');
            const blob = await response.blob();
            if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
            return {
                blob,
                filename: downloadName(
                    response.headers.get('Content-Disposition'),
                    options.downloadFilename,
                ),
            } satisfies Download;
        }
        let body: unknown;
        try {
            if (options.acceptEmpty) {
                const text = await response.text();
                body = text.length ? JSON.parse(text) : undefined;
            } else {
                body = await response.json();
            }
        } catch (error) {
            if (signal.aborted) throw error;
            if (response.ok) throw new ApiError('invalidResponse');
        }
        if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
        if (!response.ok) {
            const credentialOperation =
                options.password !== undefined ||
                (options.method === 'POST' &&
                    ['/authentication/enable', '/authentication/disable'].includes(path));
            if (credentialOperation && body === 'error.credentials.invalid')
                throw new ApiError('credentials');
            if (credentialOperation && body === 'error.credentials.too.soon')
                throw new ApiError('wait');
            if (response.status === 401) throw new ApiError('sessionExpired');
            if (response.status === 403) throw new ApiError('forbidden');
            if (response.status === 404) throw new ApiError('notFound');
            if (response.status === 400 || response.status === 422)
                throw new ApiError('validation');
            if (response.status === 409) throw new ApiError('conflict');
            throw new ApiError('server');
        }
        return body;
    } catch (error) {
        if (error instanceof ApiError) throw error;
        if (options.signal?.aborted) throw new ApiError('cancelled');
        if (timeout.signal.aborted) throw new ApiError('timeout');
        throw new ApiError('network');
    } finally {
        clearTimeout(timer);
    }
}
