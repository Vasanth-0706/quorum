import { ROLE_LABEL, setSignIn, useSignIn, USER_ID_MAX, type Role } from '../auth';

const ROLES: Role[] = ['COORDINATOR', 'DIRECTOR'];

/**
 * TEMPORARY stand-in for login: who the writes are sent as. Stored in
 * localStorage and sent as X-User-Id and X-User-Role.
 */
export function SignInPanel() {
  const signIn = useSignIn();
  const blank = !signIn.userId.trim();

  return (
    <section className="signin" aria-labelledby="signin-title">
      <div className="signin-head">
        <h2 className="signin-title" id="signin-title">
          Signed in as
        </h2>
        <span className="signin-temp">Temporary</span>
      </div>

      <div className="signin-roles" role="radiogroup" aria-label="Role">
        {ROLES.map((role) => (
          <button
            key={role}
            type="button"
            role="radio"
            aria-checked={signIn.role === role}
            onClick={() => setSignIn({ ...signIn, role })}
          >
            {ROLE_LABEL[role]}
          </button>
        ))}
      </div>

      <label className="visually-hidden" htmlFor="signin-user">
        User id
      </label>
      <input
        id="signin-user"
        className="signin-input"
        type="text"
        value={signIn.userId}
        maxLength={USER_ID_MAX}
        placeholder="User id, e.g. coord-1"
        autoComplete="off"
        spellCheck={false}
        aria-invalid={blank || undefined}
        aria-describedby="signin-note"
        onChange={(e) => setSignIn({ ...signIn, userId: e.target.value })}
      />
      <p className="signin-note" id="signin-note">
        {blank ? 'Enter a user id; writes are refused without one.' : 'Sent as X-User-Id and X-User-Role until real login.'}
      </p>
    </section>
  );
}
