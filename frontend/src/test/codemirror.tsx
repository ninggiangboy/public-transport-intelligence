import type { CodeMirrorJsonProps } from '@/components/codemirror-json';

// jsdom cannot drive CodeMirror (contenteditable and layout), so component tests run JsonEditor on a textarea that
// reports its text and lists the diagnostics it is given (setup.ts). The editor itself is covered by E2E-DLQ-02.
export default function CodeMirrorDouble({ value, onChange, diagnostics, ariaLabel, readOnly }: CodeMirrorJsonProps) {
  return (
    <div>
      <textarea
        aria-label={ariaLabel}
        value={value}
        readOnly={readOnly}
        onChange={(event) => {
          onChange(event.target.value);
        }}
      />
      <ul aria-label="Editor diagnostics">
        {(diagnostics ?? []).map((item) => (
          <li key={`${item.line}-${item.message}`}>{`${item.line}: ${item.message}`}</li>
        ))}
      </ul>
    </div>
  );
}
