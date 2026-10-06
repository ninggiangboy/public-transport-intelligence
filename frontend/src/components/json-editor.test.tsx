import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { JsonEditor } from '@/components/JsonEditor';

const value = JSON.stringify({ payload: { vehicle_id: '1742', route_id: '999' } }, null, 2);

describe('DS-08 JsonEditor', () => {
  it('loads the editor chunk on demand and shows the text it is given', async () => {
    render(
      <JsonEditor value={value} onChange={() => undefined} ariaLabel="Payload JSON" fileName="vehicle_position.json" />,
    );
    expect(screen.getByText('vehicle_position.json')).toBeInTheDocument();
    expect(await screen.findByRole('textbox', { name: 'Payload JSON' })).toHaveValue(value);
  });

  it('marks the line of the field an error points at, with its message', async () => {
    render(
      <JsonEditor
        value={value}
        onChange={() => undefined}
        ariaLabel="Payload JSON"
        fileName="vehicle_position.json"
        errors={[{ pointer: '/payload/route_id', message: 'Route 999 is not in the feed' }]}
      />,
    );
    const marks = await screen.findByRole('list', { name: 'Editor diagnostics' });
    // `{`, `"payload": {`, `"vehicle_id"`, `"route_id"`: the fourth line.
    expect(within(marks).getByText('4: Route 999 is not in the feed')).toBeInTheDocument();
  });

  it('reports what is typed', async () => {
    const seen: string[] = [];
    render(<JsonEditor value="{}" onChange={(text) => seen.push(text)} ariaLabel="Payload JSON" fileName="x.json" />);
    const editor = await screen.findByRole('textbox', { name: 'Payload JSON' });
    fireEvent.change(editor, { target: { value: '{"a":1}' } });
    expect(seen).toEqual(['{"a":1}']);
  });
});
