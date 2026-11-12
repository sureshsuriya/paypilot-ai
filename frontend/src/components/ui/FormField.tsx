import type { InputHTMLAttributes, ReactNode } from 'react';

export interface FormFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  id: string;
  label: string;
  error?: string;
  hint?: string;
  rightAdornment?: ReactNode;
}

export function FormField({
  id,
  label,
  error,
  hint,
  rightAdornment,
  className = '',
  required,
  ...inputProps
}: FormFieldProps) {
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const describedBy = error ? errorId : hint ? hintId : undefined;

  return (
    <div className="space-y-1.5">
      <label
        htmlFor={id}
        className="block text-sm font-medium text-slate-800"
      >
        {label}
        {required ? <span className="ml-0.5 text-red-600" aria-hidden="true">*</span> : null}
      </label>

      <div className="relative">
        <input
          id={id}
          required={required}
          aria-invalid={Boolean(error)}
          aria-describedby={describedBy}
          className={[
            'block w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900',
            'placeholder:text-slate-400 transition-colors duration-150',
            'focus:outline-none focus:ring-2 focus:ring-offset-0 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500',
            rightAdornment ? 'pr-11' : '',
            error
              ? 'border-red-400 focus:border-red-500 focus:ring-red-100'
              : 'border-slate-300 hover:border-slate-400 focus:border-primary-600 focus:ring-primary-100',
            className,
          ].join(' ')}
          {...inputProps}
        />
        {rightAdornment ? (
          <div className="absolute inset-y-0 right-0 flex items-center pr-2">
            {rightAdornment}
          </div>
        ) : null}
      </div>

      {error ? (
        <p id={errorId} role="alert" className="text-xs font-medium text-red-600">
          {error}
        </p>
      ) : hint ? (
        <p id={hintId} className="text-xs text-slate-500">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

