// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState } from 'react';
import { errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';

/** One abortable operation per form; stale completions cannot publish files or state. */
export function useTransfer() {
    const current = useRef<AbortController | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<ErrorCode>();
    useEffect(() => () => current.current?.abort(), []);
    function cancel() {
        current.current?.abort();
        current.current = null;
        setBusy(false);
        setError(undefined);
    }
    async function run<T>(
        operation: (signal: AbortSignal) => Promise<T>,
        done: (value: T) => void,
        failed?: () => void,
    ) {
        if (current.current) return;
        const controller = new AbortController();
        current.current = controller;
        setBusy(true);
        setError(undefined);
        try {
            const result = await operation(controller.signal);
            if (!controller.signal.aborted) done(result);
        } catch (cause) {
            if (!controller.signal.aborted) {
                setError(errorCode(cause));
                failed?.();
            }
        } finally {
            if (current.current === controller) {
                current.current = null;
                setBusy(false);
            }
        }
    }
    return { busy, error, run, cancel };
}

export function useDownload() {
    const active = useRef<string | null>(null);
    const [download, setDownload] = useState<{ url: string; filename: string }>();
    function clear() {
        if (active.current) URL.revokeObjectURL(active.current);
        active.current = null;
        setDownload(undefined);
    }
    useEffect(
        () => () => {
            if (active.current) URL.revokeObjectURL(active.current);
        },
        [],
    );
    function receive(file: { blob: Blob; filename: string }) {
        clear();
        const url = URL.createObjectURL(file.blob);
        active.current = url;
        setDownload({ url, filename: file.filename });
    }
    return { download, receive, clear };
}
