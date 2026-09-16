// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useState } from 'react';
import type { z } from 'zod';
import type { ConsoleClient } from '../../api/client';
import { errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';

export type Resource<T> = {
    data?: T;
    loading: boolean;
    error?: ErrorCode;
    updated?: Date;
};

/** Each source keeps its own error and timestamp, so a partial outage is not hidden. */
export function useResource<T>(
    client: ConsoleClient,
    path: string,
    schema: z.ZodType<T>,
    revision: number,
): Resource<T> {
    const [resource, setResource] = useState<Resource<T>>({ loading: true });
    useEffect(() => {
        setResource({ loading: true });
    }, [client, path]);
    useEffect(() => {
        const controller = new AbortController();
        setResource((previous) => ({ ...previous, loading: true }));
        void client
            .get(path, schema, controller.signal)
            .then((data) => {
                if (!controller.signal.aborted) {
                    setResource({ data, loading: false, updated: new Date() });
                }
            })
            .catch((error: unknown) => {
                if (!controller.signal.aborted) {
                    setResource((previous) => ({
                        ...previous,
                        loading: false,
                        error: errorCode(error),
                    }));
                }
            });
        return () => controller.abort();
    }, [client, path, schema, revision]);
    return resource;
}
