import { CopyButton } from '@/components/CopyButton';
import { en } from '@/i18n/en';

interface IdTextProps {
  id: string;
  /** Characters shown. Default 8. */
  length?: number;
  /** Show the copy button on hover and focus. Default true. */
  copy?: boolean;
}

/** An id as its first characters in mono; the full id is the tooltip and what the copy button copies (DOC-37 §4.6). */
export function IdText({ id, length = 8, copy = true }: IdTextProps) {
  return (
    <span className="group inline-flex items-center gap-0.5">
      <span title={id} className="font-mono text-sm">
        {id.slice(0, length)}
      </span>
      {copy ? (
        <span className="opacity-0 group-focus-within:opacity-100 group-hover:opacity-100">
          <CopyButton value={id} label={en.common.copyId} />
        </span>
      ) : null}
    </span>
  );
}
