import { useEffect, type CSSProperties } from 'react';
import { Toaster as Sonner, toast, type ToasterProps } from 'sonner';

import { useTheme } from '@/app/theme-provider';
import { toasterReady } from '@/lib/notify';

// Toasts (DOC-35 §5.5): at most 3 at once, cards with a 12 px radius; the colours come from the tokens.
export function Toaster(props: ToasterProps) {
  const { resolved } = useTheme();
  // After Sonner's own effect has subscribed (children's effects run first): queued toasts can show.
  useEffect(() => {
    toasterReady(toast);
  }, []);
  const style: CSSProperties & Record<`--${string}`, string> = {
    '--normal-bg': 'var(--popover)',
    '--normal-text': 'var(--popover-foreground)',
    '--normal-border': 'var(--border)',
    '--border-radius': 'var(--radius-lg)',
    zIndex: 'var(--z-toast)',
  };
  return <Sonner theme={resolved} visibleToasts={3} className="toaster" style={style} {...props} />;
}
