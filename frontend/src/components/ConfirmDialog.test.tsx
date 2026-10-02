import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ConfirmDialog } from '@/components/ConfirmDialog';

const reason = { label: 'Reason', min: 3, max: 200 };

function setup(props: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  const onConfirm = vi.fn<(reason?: string) => Promise<void>>(() => Promise.resolve());
  const onOpenChange = vi.fn();
  render(
    <ConfirmDialog
      open
      title="Discard this dead letter?"
      description="It leaves the review queue."
      confirmLabel="Discard"
      reason={reason}
      onConfirm={onConfirm}
      onOpenChange={onOpenChange}
      {...props}
    />,
  );
  return { onConfirm, onOpenChange, user: userEvent.setup() };
}

describe('DS-07 ConfirmDialog', () => {
  it('disables the confirm button until the reason has the minimum length', async () => {
    const { user } = setup();
    const confirm = screen.getByRole('button', { name: 'Discard' });
    expect(confirm).toBeDisabled();

    await user.type(screen.getByLabelText('Reason'), 'ab');
    expect(confirm).toBeDisabled();
    expect(screen.getByLabelText('Reason')).toHaveAttribute('aria-invalid', 'true');

    await user.type(screen.getByLabelText('Reason'), 'c');
    expect(confirm).toBeEnabled();
  });

  it('counts spaces around the reason for nothing', async () => {
    const { user } = setup();
    await user.type(screen.getByLabelText('Reason'), '  a  ');
    expect(screen.getByRole('button', { name: 'Discard' })).toBeDisabled();
  });

  it('shows the error in the dialog and stays open when onConfirm throws an ApiError', async () => {
    const error = Object.assign(new Error('conflict'), {
      status: 409,
      slug: 'dlq-invalid-state',
      traceId: 'trace-123',
      problem: { title: 'Conflict', detail: 'moved on', currentStatus: 'RESOLVED' },
    });
    const { user, onOpenChange, onConfirm } = setup({ onConfirm: vi.fn(() => Promise.reject(error)) });

    await user.type(screen.getByLabelText('Reason'), 'duplicate');
    await user.click(screen.getByRole('button', { name: 'Discard' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('This dead letter has moved on');
    expect(alert).toHaveTextContent('Its status is now RESOLVED.');
    expect(alert).toHaveTextContent('trace-123');
    expect(onOpenChange).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(onConfirm).toBeDefined();
    // The operator can fix the reason and try again.
    expect(screen.getByRole('button', { name: 'Discard' })).toBeEnabled();
  });

  it('passes the trimmed reason and closes on success', async () => {
    const { user, onConfirm, onOpenChange } = setup();
    await user.type(screen.getByLabelText('Reason'), '  duplicate record ');
    await user.click(screen.getByRole('button', { name: 'Discard' }));
    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false);
    });
    expect(onConfirm).toHaveBeenCalledWith('duplicate record');
  });

  it('sends one request however often the button is pressed', async () => {
    let finish: () => void = () => undefined;
    const onConfirm = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const { user } = setup({ onConfirm, reason: undefined });
    const confirm = screen.getByRole('button', { name: 'Discard' });
    await user.dblClick(confirm);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(confirm).toBeDisabled();
    expect(confirm).toHaveAttribute('aria-busy', 'true');
    finish();
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
  });

  it('confirms without a reason when none is asked for', async () => {
    const { user, onConfirm } = setup({ reason: undefined });
    await user.click(screen.getByRole('button', { name: 'Discard' }));
    expect(onConfirm).toHaveBeenCalledWith(undefined);
  });

  it('closes on Cancel', async () => {
    const { user, onOpenChange } = setup();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
