import { useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, createDrive } from '../api';
import { Banner } from '../components/Banner';
import { Button, buttonClass } from '../components/Button';
import { DayTrack } from '../components/DayTrack';
import { Field } from '../components/Field';
import { ChevronLeftIcon } from '../components/Icons';
import { useHealth } from '../health';
import { DAY_END, DAY_START, formatDuration, toMinutes } from '../time';

type FieldName = 'companyName' | 'driveDate' | 'startTime' | 'endTime' | 'venue';
type Values = Record<FieldName, string>;
type Errors = Partial<Record<FieldName, string>>;

const EMPTY: Values = { companyName: '', driveDate: '', startTime: '', endTime: '', venue: '' };
const ORDER: FieldName[] = ['companyName', 'driveDate', 'startTime', 'endTime', 'venue'];

function validate(values: Values): Errors {
  const errors: Errors = {};
  if (!values.companyName.trim()) errors.companyName = 'Enter the company name';
  if (!values.driveDate) errors.driveDate = 'Pick the date of the drive';
  if (!values.startTime) errors.startTime = 'Pick a start time';
  if (!values.endTime) errors.endTime = 'Pick an end time';
  const start = toMinutes(values.startTime);
  const end = toMinutes(values.endTime);
  if (start !== null && end !== null && end <= start) errors.endTime = 'End time must be after the start time';
  if (!values.venue.trim()) errors.venue = 'Enter the venue';
  return errors;
}

export function CreateDrivePage() {
  const navigate = useNavigate();
  const { reportPlacement } = useHealth();
  const formRef = useRef<HTMLFormElement>(null);

  const [values, setValues] = useState<Values>(EMPTY);
  // A field shows its error once it has been edited or left, or after a submit.
  const [shown, setShown] = useState<Partial<Record<FieldName, boolean>>>({});
  const [submitting, setSubmitting] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const errors = validate(values);
  const visibleError = (name: FieldName) => (shown[name] ? errors[name] : undefined);

  // The end-after-start rule involves two fields, so editing either one
  // reveals the end-time error.
  function change(name: FieldName, value: string) {
    setValues((v) => ({ ...v, [name]: value }));
    setShown((s) => ({ ...s, [name]: true, ...(name === 'startTime' && s.endTime ? { endTime: true } : {}) }));
  }

  function blur(name: FieldName) {
    setShown((s) => ({ ...s, [name]: true }));
  }

  function inputProps(name: FieldName) {
    return {
      name,
      value: values[name],
      onChange: (e: { target: { value: string } }) => change(name, e.target.value),
      onBlur: () => blur(name),
      className: 'input',
      required: true,
    };
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setServerError(null);
    if (Object.keys(errors).length > 0) {
      setShown({ companyName: true, driveDate: true, startTime: true, endTime: true, venue: true });
      const first = ORDER.find((name) => errors[name]);
      if (first) formRef.current?.querySelector<HTMLInputElement>(`[name="${first}"]`)?.focus();
      return;
    }

    setSubmitting(true);
    try {
      const drive = await createDrive({
        companyName: values.companyName.trim(),
        driveDate: values.driveDate,
        startTime: values.startTime.slice(0, 5),
        endTime: values.endTime.slice(0, 5),
        venue: values.venue.trim(),
      });
      reportPlacement(true);
      navigate('/drives', { state: { created: drive } });
    } catch (error) {
      setSubmitting(false);
      if (error instanceof ApiError && error.unreachable) {
        reportPlacement(false);
        setServerError("Can't reach placement-service on port 8081. Start it and try again; your entries are kept.");
      } else {
        const detail = error instanceof Error ? error.message : 'Unknown error';
        setServerError(`The drive was not created. ${detail}.`);
      }
    }
  }

  const start = toMinutes(values.startTime);
  const end = toMinutes(values.endTime);

  return (
    <>
      <Link to="/drives" className="back-link">
        <ChevronLeftIcon size={16} />
        Drives
      </Link>
      <header className="page-header">
        <h1 className="page-title">Create drive</h1>
      </header>

      {serverError && (
        <Banner tone="error" onDismiss={() => setServerError(null)}>
          {serverError}
        </Banner>
      )}

      <div className="create-layout">
        <form ref={formRef} className="card" onSubmit={handleSubmit} noValidate aria-label="New drive">
          <div className="card-body form-stack">
            <Field id="companyName" label="Company" error={visibleError('companyName')}>
              <input {...inputProps('companyName')} type="text" autoComplete="organization" placeholder="e.g. Oracle" />
            </Field>

            <div className="form-row-3">
              <Field id="driveDate" label="Date" error={visibleError('driveDate')}>
                <input {...inputProps('driveDate')} type="date" />
              </Field>
              <Field id="startTime" label="Starts" error={visibleError('startTime')}>
                <input {...inputProps('startTime')} type="time" />
              </Field>
              <Field id="endTime" label="Ends" error={visibleError('endTime')}>
                <input {...inputProps('endTime')} type="time" />
              </Field>
            </div>

            <Field
              id="venue"
              label="Venue"
              error={visibleError('venue')}
              helper="Use the room name students will see on the notice"
            >
              <input {...inputProps('venue')} type="text" placeholder="e.g. Seminar Hall, C Block" />
            </Field>
          </div>

          <div className="form-footer">
            <Link to="/drives" className={buttonClass('secondary')}>
              Cancel
            </Link>
            <Button type="submit" loading={submitting} loadingText="Creating drive">
              Create drive
            </Button>
          </div>
        </form>

        <aside>
          <section className="card card-body" aria-labelledby="day-title">
            <h2 className="card-title" id="day-title">
              In the college day
            </h2>
            <div style={{ marginTop: 16 }}>
              <DayTrack start={start} end={end} height={14} showTicks />
            </div>
            <p className="side-note" aria-live="polite">
              <DaySummary start={start} end={end} />
            </p>
          </section>

          <section className="card card-body" aria-labelledby="steps-title">
            <h2 className="card-title" id="steps-title">
              What happens when you create it
            </h2>
            <ol className="steps">
              <li>
                <span className="step-number">1</span>
                <span>The drive is saved by the placement service.</span>
              </li>
              <li>
                <span className="step-number">2</span>
                <span>A drive event is published to Kafka.</span>
              </li>
              <li>
                <span className="step-number">3</span>
                <span>The attendance service receives it, even if it was down at the time.</span>
              </li>
            </ol>
          </section>
        </aside>
      </div>
    </>
  );
}

function DaySummary({ start, end }: { start: number | null; end: number | null }) {
  if (start === null || end === null) {
    return <>Pick a start and end time to see the drive against the college day.</>;
  }
  if (end <= start) {
    return <>Fix the end time to see how much of the day this drive takes.</>;
  }
  const outside = start < DAY_START || end > DAY_END;
  return (
    <>
      Takes <strong>{formatDuration(end - start)}</strong> of the 9 h college day.
      {outside && ' Part of it falls outside 8:00 to 17:00, so the bar is cut at the edge.'}
    </>
  );
}
