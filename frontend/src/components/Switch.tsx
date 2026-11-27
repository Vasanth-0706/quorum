interface SwitchProps {
  id: string;
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}

/** An on/off toggle built on a checkbox, so it works with the keyboard and screen readers. */
export function Switch({ id, label, checked, onChange }: SwitchProps) {
  return (
    <label className="switch" htmlFor={id}>
      <input
        id={id}
        type="checkbox"
        role="switch"
        className="switch-input"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="switch-track" aria-hidden="true">
        <span className="switch-thumb" />
      </span>
      <span className="switch-label">{label}</span>
    </label>
  );
}
