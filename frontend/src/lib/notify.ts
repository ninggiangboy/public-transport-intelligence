import type { ExternalToast, toast as sonnerToast } from 'sonner';

// Toasts without sonner in the initial bundle (DOC-34 §7): the shell mounts the Toaster lazily, and a toast raised
// before it is mounted waits for it instead of being lost.

type Toast = typeof sonnerToast;

let resolveReady: (toast: Toast) => void = () => undefined;
const ready = new Promise<Toast>((resolve) => {
  resolveReady = resolve;
});

/** Called by the Toaster once it listens; toasts raised earlier show from then on. */
export function toasterReady(toast: Toast) {
  resolveReady(toast);
}

export const notify = {
  message(text: string, options?: ExternalToast) {
    void ready.then((toast) => toast(text, options));
  },
  success(text: string, options?: ExternalToast) {
    void ready.then((toast) => toast.success(text, options));
  },
  error(text: string, options?: ExternalToast) {
    void ready.then((toast) => toast.error(text, options));
  },
};
