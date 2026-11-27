import type { ButtonHTMLAttributes, ReactNode } from 'react';

export type ButtonVariant = 'primary' | 'secondary';

/** Class names for a button look, so links can be styled as buttons too. */
export function buttonClass(variant: ButtonVariant = 'primary', small = false): string {
  return `btn btn-${variant}${small ? ' btn-small' : ''}`;
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  small?: boolean;
  loading?: boolean;
  loadingText?: string;
  icon?: ReactNode;
}

export function Button({
  variant = 'primary',
  small = false,
  loading = false,
  loadingText,
  icon,
  children,
  className,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      className={`${buttonClass(variant, small)}${className ? ` ${className}` : ''}`}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <span className="spinner" aria-hidden="true" /> : icon}
      {loading && loadingText ? loadingText : children}
    </button>
  );
}
