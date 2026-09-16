// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';
import type { ZodType } from 'zod';
import {
    cookieSessionSchema,
    deviceSchema,
    deviceSettingsPatchSchema,
    devicesSchema,
    tokenSchema,
} from './contracts';
import type { CookieSession, Token, Device, DeviceSettingsPatch } from './contracts';
import { ApiError, errorCode, request } from './http';
import type { Download, ErrorCode, Transport } from './http';

export type Session =
    | {
          phase: 'loading' | 'login' | 'ready' | 'signedOut' | 'expired';
          passwordProtected?: boolean;
      }
    | { phase: 'error'; error: ErrorCode };

/** HTTPS uses HttpOnly sessions; the retained HTTP adapter keeps bearer tokens only in memory. */
export class ConsoleClient {
    private token?: Token;
    private cookie?: CookieSession;
    private readonly cookieMode: boolean;
    // Set-Cookie is applied by the browser before JavaScript can reject a stale
    // response. Keep all cookie-issuing operations and revocations in order.
    private cookieOperations: Promise<void> = Promise.resolve();
    private revocationToken?: string;
    private revocationRequired = false;
    private session: Session = { phase: 'loading' };
    private generation = 0;
    private renewal?: Promise<void>;
    private lastActivity = 0;
    private readonly idleTimeoutMs = 20 * 60 * 1000;
    private listeners = new Set<() => void>();

    constructor(
        private transport: Transport = (...args) => fetch(...args),
        private now = Date.now,
        mode: 'auto' | 'cookie' | 'bearer' = 'auto',
    ) {
        this.cookieMode =
            mode === 'cookie' ||
            (mode === 'auto' && typeof location !== 'undefined' && location.protocol === 'https:');
    }
    snapshot = (): Session => this.session;
    subscribe = (listener: () => void) => {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    };
    private publish(session: Session) {
        this.session = session;
        this.listeners.forEach((listener) => listener());
    }
    private parseToken(value: unknown): Token {
        const parsed = tokenSchema.safeParse(value);
        if (!parsed.success || parsed.data.expiresOn * 1000 <= this.now())
            throw new ApiError('invalidResponse');
        return parsed.data;
    }
    private assertGeneration(generation: number) {
        if (generation !== this.generation) throw new ApiError('cancelled');
    }

    async bootstrap(): Promise<void> {
        const generation = ++this.generation;
        this.token = undefined;
        // Keep the last known CSRF until bootstrap settles: logout during this
        // loading state must still be able to revoke the existing browser cookie.
        this.renewal = undefined;
        this.publish({ phase: 'loading' });
        try {
            if (this.cookieMode) {
                await this.flushRevocation();
                this.assertGeneration(generation);
                await this.cookieOperation('', undefined, generation);
                return;
            }
            const token = this.parseToken(
                await request(this.transport, '/authentication/token/ADMINCONSOLE'),
            );
            this.assertGeneration(generation);
            this.token = token;
            this.lastActivity = this.now();
            this.publish({
                phase: token.passwordRequired ? 'login' : 'ready',
                passwordProtected: token.passwordRequired,
            });
        } catch (error) {
            if (generation === this.generation)
                this.publish({ phase: 'error', error: errorCode(error) });
        }
    }

    async login(password: string): Promise<void> {
        if (this.session.phase !== 'login') throw new ApiError('sessionExpired');
        const generation = this.generation;
        if (this.cookieMode) {
            try {
                await this.cookieOperation('/login', password, generation);
            } catch (error) {
                if (generation === this.generation && errorCode(error) === 'sessionExpired')
                    this.expire();
                throw error;
            }
            return;
        }
        const token = this.parseToken(
            await request(this.transport, '/authentication/login/ADMINCONSOLE', { password }),
        );
        this.assertGeneration(generation);
        this.token = token;
        this.lastActivity = this.now();
        this.publish({ phase: 'ready', passwordProtected: token.passwordRequired });
    }

    async loginWait(): Promise<number> {
        const generation = this.generation;
        if ((!this.token && !this.cookie) || this.session.phase !== 'login')
            throw new ApiError('sessionExpired');
        const result = await request(this.transport, '/authentication/wait', {
            token: this.token?.token,
            cookieSession: this.cookieMode,
            csrfToken: this.cookie?.csrfToken,
        });
        this.assertGeneration(generation);
        if (typeof result !== 'number' || !Number.isFinite(result))
            throw new ApiError('invalidResponse');
        return Math.max(0, Math.ceil(result));
    }

    // Only explicit user input counts as activity; polling and renewal never do.
    markActive(): void {
        this.checkIdle();
        if (this.session.phase === 'ready') this.lastActivity = this.now();
    }
    checkIdle(): void {
        if (this.session.phase === 'ready' && this.now() - this.lastActivity >= this.idleTimeoutMs)
            this.expire();
    }

    logout(): void {
        const csrf = this.cookie?.csrfToken;
        ++this.generation;
        this.token = undefined;
        this.cookie = undefined;
        this.renewal = undefined;
        this.publish({ phase: 'signedOut' });
        if (this.cookieMode) {
            this.revocationRequired = true;
            this.revocationToken = csrf ?? this.revocationToken;
            const generation = this.generation;
            void this.flushRevocation().catch((error) => {
                if (generation === this.generation)
                    this.publish({ phase: 'error', error: errorCode(error) });
            });
        }
    }

    private expire() {
        const csrf = this.cookie?.csrfToken;
        ++this.generation;
        this.token = undefined;
        this.cookie = undefined;
        this.renewal = undefined;
        this.publish({ phase: 'expired' });
        if (this.cookieMode) {
            this.revocationRequired = true;
            this.revocationToken = csrf ?? this.revocationToken;
            void this.flushRevocation().catch(() => {
                /* Bootstrap retries server revocation before opening another session. */
            });
        }
    }

    private enqueueCookie<T>(operation: () => Promise<T>): Promise<T> {
        const pending = this.cookieOperations.then(operation);
        // A failed operation must not poison subsequent revocation retries.
        this.cookieOperations = pending.then(
            () => undefined,
            () => undefined,
        );
        return pending;
    }

    private flushRevocation(): Promise<void> {
        return this.enqueueCookie(() => this.revokeCookie());
    }

    /** Called only while owning the cookie-operation queue. */
    private async revokeCookie(): Promise<void> {
        if (!this.revocationRequired) return;
        const recoverCsrf = async () => {
            // A lost response or another tab may have replaced the HttpOnly
            // cookie. Bootstrap solely to revoke that family, never to publish it.
            const result = await request(this.transport, '/authentication/session', {
                method: 'POST',
                cookieSession: true,
            });
            const parsed = cookieSessionSchema.safeParse(result);
            if (!parsed.success) throw new ApiError('invalidResponse');
            this.revocationToken = parsed.data.csrfToken;
        };
        const cleared = () => {
            this.revocationToken = undefined;
            this.revocationRequired = false;
        };
        try {
            if (!this.revocationToken) await recoverCsrf();
            for (let attempt = 0; attempt < 2; attempt++) {
                const csrf = this.revocationToken;
                try {
                    const result = await request(this.transport, '/authentication/session/logout', {
                        method: 'POST',
                        cookieSession: true,
                        csrfToken: csrf,
                        acceptEmpty: true,
                    });
                    if (result !== undefined && result !== null)
                        throw new ApiError('invalidResponse');
                    if (this.revocationToken === csrf) cleared();
                    return;
                } catch (error) {
                    if (errorCode(error) !== 'forbidden' || attempt !== 0) throw error;
                    // One bounded recovery handles another tab's different family.
                    // A second rejection stays pending for an explicit user retry.
                    await recoverCsrf();
                }
            }
        } catch (error) {
            if (errorCode(error) === 'sessionExpired') {
                // The server clears rejected/revoked cookies on bootstrap/logout.
                cleared();
                return;
            }
            throw error;
        }
    }

    private cookieOperation(
        operation: '' | '/login' | '/renew',
        password: string | undefined,
        generation: number,
    ): Promise<void> {
        return this.enqueueCookie(async () => {
            this.assertGeneration(generation);
            await this.revokeCookie();
            this.assertGeneration(generation);
            if (operation === '/login' && this.session.phase !== 'login')
                throw new ApiError('cancelled');
            const result = await request(this.transport, `/authentication/session${operation}`, {
                method: 'POST',
                cookieSession: true,
                csrfToken: this.cookie?.csrfToken,
                password,
            });
            const parsed = cookieSessionSchema.safeParse(result);
            if (!parsed.success || parsed.data.expiresOn * 1000 <= this.now())
                throw new ApiError('invalidResponse');
            if (generation !== this.generation) {
                // This response may have installed a cookie even if a newer bootstrap
                // is already waiting. Revoke it before that bootstrap can proceed.
                this.revocationRequired = true;
                this.revocationToken = parsed.data.csrfToken;
                await this.revokeCookie();
                throw new ApiError('cancelled');
            }
            if (operation !== '' && !parsed.data.authenticated)
                throw new ApiError('invalidResponse');
            this.cookie = parsed.data;
            if (operation !== '/renew') this.lastActivity = this.now();
            this.publish({
                phase: parsed.data.authenticated ? 'ready' : 'login',
                passwordProtected: parsed.data.passwordRequired,
            });
        });
    }

    private async readyCookie(): Promise<string> {
        if (this.session.phase !== 'ready') throw new ApiError('sessionExpired');
        if (!this.cookie || this.cookie.expiresOn * 1000 <= this.now()) {
            this.expire();
            throw new ApiError('sessionExpired');
        }
        if (this.cookie.expiresOn * 1000 - this.now() <= 60_000) {
            if (!this.renewal) {
                const generation = this.generation;
                this.renewal = this.cookieOperation('/renew', undefined, generation).finally(() => {
                    if (generation === this.generation) this.renewal = undefined;
                });
            }
            await this.renewal;
        }
        return '';
    }

    private async readyToken(): Promise<string> {
        this.checkIdle();
        if (this.cookieMode) return this.readyCookie();
        if (!this.token || this.session.phase !== 'ready') throw new ApiError('sessionExpired');
        if (this.token.expiresOn * 1000 <= this.now()) {
            this.expire();
            throw new ApiError('sessionExpired');
        }
        if (this.token.expiresOn * 1000 - this.now() <= 60_000) {
            if (!this.renewal) {
                const generation = this.generation;
                const previousToken = this.token.token;
                this.renewal = (async () => {
                    try {
                        const result = await request(
                            this.transport,
                            '/authentication/renew/ADMINCONSOLE',
                            { token: previousToken },
                        );
                        this.assertGeneration(generation);
                        this.token = this.parseToken(result);
                        if (
                            this.session.phase === 'ready' &&
                            this.session.passwordProtected !== this.token.passwordRequired
                        ) {
                            this.publish({
                                phase: 'ready',
                                passwordProtected: this.token.passwordRequired,
                            });
                        }
                    } catch (error) {
                        if (generation === this.generation && errorCode(error) === 'sessionExpired')
                            this.expire();
                        throw error;
                    } finally {
                        if (generation === this.generation) this.renewal = undefined;
                    }
                })();
            }
            await this.renewal;
        }
        if (!this.token || this.session.phase !== 'ready') throw new ApiError('sessionExpired');
        return this.token.token;
    }

    devices(signal?: AbortSignal): Promise<Device[]> {
        return this.get('/devices', devicesSchema, signal);
    }

    get<T>(path: string, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
        return this.authenticated(path, schema, { signal });
    }

    getBlockers<T>(schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
        return this.authenticated('/blockers/', schema, { signal, apiRoot: '/api' });
    }

    post<T>(path: string, body: unknown, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
        return this.authenticated(path, schema, { method: 'POST', body, signal });
    }

    put<T>(path: string, body: unknown, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
        return this.authenticated(path, schema, { method: 'PUT', body, signal });
    }

    patch<T>(path: string, body: unknown, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
        return this.authenticated(path, schema, { method: 'PATCH', body, signal });
    }

    delete<T>(path: string, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
        return this.authenticated(path, schema, { method: 'DELETE', signal });
    }

    wireGuardControl<T>(
        path: string,
        method: 'GET' | 'PUT' | 'POST' | 'DELETE',
        body: unknown | undefined,
        schema: ZodType<T>,
        signal?: AbortSignal,
    ): Promise<T> {
        const match =
            /^\/wireguard\/profiles(?:\/([a-z][a-z0-9-]{0,31})(?:\/(connect|disconnect|cancel))?)?$/.exec(
                path,
            );
        const allowed =
            match &&
            (match[2]
                ? method === 'POST'
                : match[1]
                  ? ['GET', 'PUT', 'DELETE'].includes(method)
                  : method === 'GET');
        if (!allowed || ((method === 'GET' || method === 'DELETE') && body !== undefined))
            return Promise.reject(new ApiError('validation'));
        // Java waits up to 40s for the native control agent (30s operation + rollback).
        return this.authenticated(path, schema, { method, body, signal, timeoutMs: 45_000 });
    }

    discoverDhcpServers<T>(schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
        // Legacy NetworkService uses 20s for the DHCP discovery service's 10s scan.
        return this.authenticated('/network/dhcpservers', schema, { signal, timeoutMs: 20_000 });
    }

    deleteVoid(path: string, signal?: AbortSignal): Promise<void> {
        return this.authenticated(
            path,
            z.union([z.undefined(), z.null()]).transform(() => undefined),
            {
                method: 'DELETE',
                signal,
                acceptEmpty: true,
            },
        );
    }

    configBackupPost<T>(
        operation: 'export' | 'verify' | 'import',
        body: unknown,
        schema: ZodType<T>,
        signal?: AbortSignal,
    ): Promise<T> {
        if (!['export', 'verify', 'import'].includes(operation))
            return Promise.reject(new ApiError('validation'));
        return this.authenticated(`/configbackup/${operation}`, schema, {
            method: 'POST',
            body,
            signal,
            apiRoot: '/api',
            timeoutMs: 60_000,
        });
    }

    uploadConfigBackup<T>(file: File, schema: ZodType<T>, signal?: AbortSignal): Promise<T> {
        if (!file || file.size === 0 || file.size > 1_048_576)
            return Promise.reject(new ApiError('validation'));
        return this.authenticated('/configbackup/upload', schema, {
            method: 'PUT',
            rawBody: file,
            signal,
            apiRoot: '/api',
            timeoutMs: 60_000,
        });
    }

    downloadConfigBackup(fileReference: string, signal?: AbortSignal): Promise<Download> {
        if (
            !fileReference ||
            fileReference.includes('/') ||
            fileReference.includes('\\') ||
            fileReference === '..' ||
            fileReference === '.'
        )
            return Promise.reject(new ApiError('validation'));
        return this.download(
            `/configbackup/download/${encodeURIComponent(fileReference)}`,
            'eblocker-config.eblcfg',
            '/api',
            signal,
        );
    }

    downloadDiagnostics(signal?: AbortSignal): Promise<Download> {
        return this.download(
            '/diagnostics/download',
            'eblocker-diagnostics-report.zip',
            '/api/adminconsole',
            signal,
        );
    }

    private download(
        path: string,
        filename: string,
        apiRoot: '/api' | '/api/adminconsole',
        signal?: AbortSignal,
    ): Promise<Download> {
        const schema = z.object({
            blob: z.custom<Blob>(
                (value) =>
                    value !== null &&
                    typeof value === 'object' &&
                    'arrayBuffer' in value &&
                    'size' in value,
            ),
            filename: z.string().min(1),
        });
        return this.authenticated(path, schema, {
            signal,
            apiRoot,
            downloadFilename: filename,
            timeoutMs: 60_000,
        });
    }

    getVpnDeviceStatus<T>(id: string, schema: ZodType<T>, signal?: AbortSignal): Promise<T | null> {
        if (!id) return Promise.reject(new ApiError('validation'));
        return this.authenticated(
            `/vpn/profile/status/${encodeURIComponent(id)}`,
            z.union([schema, z.null(), z.undefined()]).transform((value) => value ?? null),
            { signal, acceptEmpty: true },
        );
    }

    postVoid(path: string, body: unknown, signal?: AbortSignal): Promise<void> {
        return this.authenticated(
            path,
            z.union([z.undefined(), z.null()]).transform(() => undefined),
            {
                method: 'POST',
                body,
                signal,
                acceptEmpty: true,
            },
        );
    }

    putVoid(path: string, body: unknown, signal?: AbortSignal): Promise<void> {
        return this.authenticated(
            path,
            z.union([z.undefined(), z.null()]).transform(() => undefined),
            {
                method: 'PUT',
                body,
                signal,
                acceptEmpty: true,
            },
        );
    }

    updateDevice(id: string, patch: DeviceSettingsPatch): Promise<Device> {
        const parsed = deviceSettingsPatchSchema.safeParse(patch);
        if (!id || !parsed.success) return Promise.reject(new ApiError('validation'));
        return this.authenticated(`/devices/${encodeURIComponent(id)}/settings`, deviceSchema, {
            method: 'PATCH',
            body: parsed.data,
        });
    }

    private async authenticated<T>(
        path: string,
        schema: ZodType<T>,
        options: {
            signal?: AbortSignal;
            method?: 'GET' | 'PATCH' | 'POST' | 'PUT' | 'DELETE';
            body?: unknown;
            acceptEmpty?: boolean;
            apiRoot?: '/api/adminconsole' | '/api';
            rawBody?: Blob;
            downloadFilename?: string;
            timeoutMs?: number;
        },
    ): Promise<T> {
        if (this.session.phase !== 'ready') throw new ApiError('sessionExpired');
        const generation = this.generation;
        try {
            const token = await this.readyToken();
            this.assertGeneration(generation);
            const result = await request(this.transport, path, {
                ...options,
                token,
                cookieSession: this.cookieMode,
                csrfToken: this.cookie?.csrfToken,
            });
            this.assertGeneration(generation);
            const parsed = schema.safeParse(result);
            if (!parsed.success) throw new ApiError('invalidResponse');
            return parsed.data;
        } catch (error) {
            if (generation === this.generation && errorCode(error) === 'sessionExpired')
                this.expire();
            throw error;
        }
    }
}
