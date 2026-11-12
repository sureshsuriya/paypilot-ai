import { useState } from 'react';
import { FormField, type FormFieldProps } from './FormField';
import { EyeIcon, EyeOffIcon } from './Icons';

export type PasswordInputProps = Omit<FormFieldProps, 'type' | 'rightAdornment'>;

export function PasswordInput(props: PasswordInputProps) {
  const [visible, setVisible] = useState(false);

  return (
    <FormField
      {...props}
      type={visible ? 'text' : 'password'}
      rightAdornment={
        <button
          type="button"
          onClick={() => setVisible((prev) => !prev)}
          aria-label={visible ? 'Hide password' : 'Show password'}
          aria-pressed={visible}
          disabled={props.disabled}
          className="rounded-md p-1.5 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700 focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:pointer-events-none disabled:opacity-50"
        >
          {visible ? <EyeOffIcon /> : <EyeIcon />}
        </button>
      }
    />
  );
}

