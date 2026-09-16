// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useId, useRef } from 'react';
import type { ReactNode, RefObject } from 'react';

/** Inline confirmation, with focus returned to the control that opened it. */
export function Confirmation({
    title,
    description,
    cancel,
    cancelLabel,
    disabled,
    returnFocusRef,
    children,
}: {
    title: string;
    description: string;
    cancel: () => void;
    cancelLabel: string;
    disabled?: boolean;
    returnFocusRef?: RefObject<HTMLElement | null>;
    children: ReactNode;
}) {
    const titleId = useId();
    const descriptionId = useId();
    const cancelButton = useRef<HTMLButtonElement>(null);
    useEffect(() => {
        // Disabling an opener can move browser focus to body before this effect.
        const previous = returnFocusRef?.current ?? document.activeElement;
        cancelButton.current?.focus();
        return () => {
            if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
        };
    }, [returnFocusRef]);
    return (
        <section
            className="protection-confirm"
            role="dialog"
            aria-labelledby={titleId}
            aria-describedby={descriptionId}
        >
            <h3 id={titleId}>{title}</h3>
            <p id={descriptionId}>{description}</p>
            <div className="protection-actions">
                <button
                    ref={cancelButton}
                    className="button secondary"
                    onClick={cancel}
                    disabled={disabled}
                >
                    {cancelLabel}
                </button>
                {children}
            </div>
        </section>
    );
}
