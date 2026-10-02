import * as RadioGroupPrimitive from '@radix-ui/react-radio-group';
import type * as React from 'react';

export function RadioGroup(props: React.ComponentProps<typeof RadioGroupPrimitive.Root>) {
  return <RadioGroupPrimitive.Root {...props} />;
}

export function RadioGroupItem(props: React.ComponentProps<typeof RadioGroupPrimitive.Item>) {
  return <RadioGroupPrimitive.Item {...props} />;
}
