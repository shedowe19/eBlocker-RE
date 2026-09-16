// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useState } from 'react';
import { errorCode } from '../../api/http';
import type { Resource } from '../system/resource';

export function useLoad<T>(
    loader: (signal: AbortSignal) => Promise<T>,
    revision: number,
): Resource<T> {
    const [resource, setResource] = useState<Resource<T>>({ loading: true });
    useEffect(() => {
        setResource({ loading: true });
    }, [loader]);
    useEffect(() => {
        const controller = new AbortController();
        setResource((previous) => ({ ...previous, loading: true }));
        void loader(controller.signal)
            .then((data) => {
                if (!controller.signal.aborted)
                    setResource({ data, loading: false, updated: new Date() });
            })
            .catch((error: unknown) => {
                if (!controller.signal.aborted)
                    setResource((previous) => ({
                        ...previous,
                        loading: false,
                        error: errorCode(error),
                    }));
            });
        return () => controller.abort();
    }, [loader, revision]);
    return resource;
}
