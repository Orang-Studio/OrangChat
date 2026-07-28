import type { ReactNode } from "react";
import { Link, NavLink } from "react-router-dom";
import { LogoMark } from "../../components/LogoMark";
import { cn } from "../../lib/cn";

export type LegalDocument =
  | "terms"
  | "privacy"
  | "cookies"
  | "guidelines"
  | "notice";

const EFFECTIVE_DATE = "25 July 2026";
const CONTACT_EMAIL = "admin@oranges.lt";

const DOCUMENTS: Array<{ id: LegalDocument; label: string; path: string }> = [
  { id: "terms", label: "Terms", path: "/terms" },
  { id: "privacy", label: "Privacy", path: "/privacy" },
  { id: "cookies", label: "Cookies", path: "/cookies" },
  { id: "guidelines", label: "Guidelines", path: "/guidelines" },
  { id: "notice", label: "Legal notice", path: "/legal-notice" },
];

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <h2 className="text-xl font-semibold text-ink">{title}</h2>
      <div className="space-y-3 text-sm leading-7 text-ink-secondary">{children}</div>
    </section>
  );
}

function Terms() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">Effective {EFFECTIVE_DATE}</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">Terms of Service</h1>
        <p className="mt-3 leading-7 text-ink-secondary">
          These terms govern your use of OrangChat, including its website, desktop application,
          Android application, messaging, calls, servers, integrations, and related services.
        </p>
      </header>

      <Section title="1. Who provides OrangChat">
        <p>
          OrangChat is operated by Oranges.LT in Lithuania. Contact us at{" "}
          <a className="oc-link" href={`mailto:${CONTACT_EMAIL}`}>
            {CONTACT_EMAIL}
          </a>
          . More operator information is available in our{" "}
          <Link className="oc-link" to="/legal-notice">
            Legal Notice
          </Link>
          .
        </p>
      </Section>

      <Section title="2. Accepting these terms">
        <p>
          By creating an account, accessing OrangChat, or continuing to use it after being told
          about an updated version of these terms, you agree to these terms and the Community
          Guidelines. If you do not agree, do not use the service.
        </p>
        <p>
          You must be at least 14 years old. If the law where you live requires parental or
          guardian permission, you may use OrangChat only after obtaining that permission. A
          parent or guardian who permits a minor to use OrangChat should help them understand
          these terms and use the service safely.
        </p>
      </Section>

      <Section title="3. Your account">
        <ul className="list-disc space-y-2 pl-5">
          <li>Provide accurate registration information and keep it current.</li>
          <li>Keep your password, backup codes, devices, and authentication methods secure.</li>
          <li>Do not sell, transfer, share, or impersonate another person through an account.</li>
          <li>Tell us promptly if you believe your account or the service has been compromised.</li>
          <li>You are responsible for activity performed through your account until you notify us.</li>
        </ul>
      </Section>

      <Section title="4. Your content">
        <p>
          You keep ownership of messages, files, profile information, server content, custom
          emoji, sounds, events, and other material you submit. You give OrangChat a worldwide,
          non-exclusive, royalty-free licence to host, store, reproduce, process, transmit,
          display, and adapt that content only as needed to operate, secure, improve, and provide
          the service. This licence ends when the content is deleted, except for copies that must
          remain temporarily in backups, security records, or material shared with others.
        </p>
        <p>
          You confirm that you have the rights needed to submit your content and that its use on
          OrangChat does not violate law, privacy, intellectual property, or these terms.
        </p>
      </Section>

      <Section title="5. Rules for using the service">
        <p>You must follow the Community Guidelines. You must not:</p>
        <ul className="list-disc space-y-2 pl-5">
          <li>Use OrangChat for illegal, fraudulent, deceptive, or harmful activity.</li>
          <li>Exploit, groom, endanger, or sexualise a minor.</li>
          <li>Threaten violence, harass people, publish private information, or promote hatred.</li>
          <li>Distribute malware, steal credentials, evade access controls, or disrupt the service.</li>
          <li>Send spam, run abusive automation, scrape users, or manipulate platform features.</li>
          <li>Upload content you do not have permission to use.</li>
          <li>Evade a suspension, ban, rate limit, moderation action, or technical restriction.</li>
        </ul>
      </Section>

      <Section title="6. Servers and moderation">
        <p>
          Server owners and moderators may set additional rules and may remove content or members
          from their communities. Their actions are their own. OrangChat may investigate reports,
          restrict visibility, remove content, disable features, suspend accounts, preserve
          evidence, or contact competent authorities when reasonably necessary.
        </p>
        <p>
          We aim to apply restrictions diligently, objectively, and proportionately. If we take a
          platform-level action against your account or content, you may ask for a review by
          emailing us with the relevant account and content details.
        </p>
      </Section>

      <Section title="7. Software and third-party services">
        <p>
          We give you a personal, limited, revocable, non-transferable right to use OrangChat
          software for its intended purpose. You may not bypass security measures, misuse private
          interfaces, or reverse engineer the software where law permits us to restrict that
          activity.
        </p>
        <p>
          OrangChat can connect to or display third-party services. Those services have their own
          terms and privacy practices. We do not control and are not responsible for third-party
          content, availability, or conduct.
        </p>
      </Section>

      <Section title="8. Availability and changes">
        <p>
          OrangChat is currently provided without a paid subscription. We may change, add, limit,
          or discontinue features and may perform maintenance. We do not promise uninterrupted,
          error-free, or permanently available service. We will provide reasonable notice when a
          material change is likely to significantly affect users, where practical.
        </p>
      </Section>

      <Section title="9. Suspension and termination">
        <p>
          You may stop using OrangChat at any time and may delete your account in Security
          settings. You must transfer or delete servers you own first. Account deletion removes
          identifying profile and account data, but messages may remain under a Deleted User
          identity so other participants retain their conversation history. You can separately
          delete your message history before deleting your account.
        </p>
        <p>
          We may suspend or terminate access for a serious or repeated breach, legal requirement,
          security risk, or material harm to users or the service. Immediate action may be taken
          where delay would create risk. Otherwise, we will use reasonable efforts to explain the
          action and available review process.
        </p>
      </Section>

      <Section title="10. Disclaimers and liability">
        <p>
          To the fullest extent permitted by law, OrangChat is provided as available and without
          warranties beyond those that cannot legally be excluded. We are not responsible for
          user content or for indirect, incidental, special, consequential, or punitive losses
          arising from use of the service.
        </p>
        <p>
          Nothing in these terms excludes liability that cannot lawfully be excluded, including
          liability for intentional misconduct, gross negligence, or mandatory consumer rights.
          If you are a consumer, you keep all protections given to you by the law of your country.
        </p>
      </Section>

      <Section title="11. Governing law and disputes">
        <p>
          Lithuanian law governs these terms, without taking away mandatory protections available
          to consumers in their country of residence. Please contact us first so we can try to
          resolve a concern. Consumers may bring a claim before any court that has jurisdiction
          under applicable consumer law. Other claims are subject to the courts of Lithuania.
        </p>
      </Section>

      <Section title="12. Changes to these terms">
        <p>
          We may update these terms for legal, security, operational, or product reasons. We will
          provide notice of significant changes through the service or another appropriate
          channel. The effective date at the top identifies the current version.
        </p>
      </Section>
    </>
  );
}

function Privacy() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">Effective {EFFECTIVE_DATE}</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">Privacy Policy</h1>
        <p className="mt-3 leading-7 text-ink-secondary">
          OrangChat is privacy-focused. We do not track you across the web, build advertising
          profiles, run product analytics, sell personal data, or collect information simply
          because it might be useful later. This policy explains the limited information that
          must be processed to provide the web, desktop, and Android service you request.
        </p>
      </header>

      <Section title="1. Controller and contact">
        <p>
          Oranges.LT, Lithuania, is the controller for personal data processed to provide
          OrangChat. For privacy questions or to exercise your rights, email{" "}
          <a className="oc-link" href={`mailto:${CONTACT_EMAIL}`}>
            {CONTACT_EMAIL}
          </a>
          .
        </p>
      </Section>

      <Section title="2. Data we process">
        <p>
          We minimise data collection. If information is not needed to provide a feature, protect
          the service, or meet a legal duty, we do not ask for or intentionally retain it.
        </p>
        <ul className="list-disc space-y-2 pl-5">
          <li>
            <strong>Account data:</strong> email address, username, display name, password hash,
            OAuth login identifiers, profile fields, privacy preferences, and security settings.
          </li>
          <li>
            <strong>Content and social data:</strong> messages, attachments, reactions, drafts,
            friendships, memberships, roles, bans, events, server settings, and linked profiles.
          </li>
          <li>
            <strong>Voice and presence data:</strong> call participation, online status, device
            type, and short-lived credentials used to connect calls.
          </li>
          <li>
            <strong>Security and technical data:</strong> IP address, user agent, session records,
            authentication tokens, rate-limit records, audit logs, push subscriptions, error
            details, and request metadata.
          </li>
          <li>
            <strong>Device data:</strong> locally stored theme, accessibility, notification,
            download, plugin, and interface preferences.
          </li>
          <li>
            <strong>Integration data:</strong> identifiers, names, profile links, and OAuth tokens
            needed for account connections you choose to enable.
          </li>
        </ul>
      </Section>

      <Section title="3. Why we process data">
        <ul className="list-disc space-y-2 pl-5">
          <li>
            <strong>Contract:</strong> to create accounts, deliver messages and calls, store
            content, manage communities, authenticate sessions, and provide requested features.
          </li>
          <li>
            <strong>Legitimate interests:</strong> to secure the service, prevent abuse, debug
            failures, moderate content, maintain reliability, and understand operational usage.
          </li>
          <li>
            <strong>Legal obligations:</strong> to respond to valid legal requests, protect
            rights, retain required records, and meet regulatory duties.
          </li>
          <li>
            <strong>Consent:</strong> where you choose optional device permissions, push
            notifications, or an optional connection that legally requires consent. You may
            withdraw consent without affecting earlier processing.
          </li>
        </ul>
        <p>
          OrangChat has no advertising, analytics SDK, cross-site tracker, or data broker
          relationship. We do not sell personal data, use behavioural advertising, or create
          profiles for marketing.
        </p>
      </Section>

      <Section title="4. How content is shared">
        <p>
          Messages and profile information are shared with the people and communities you choose,
          according to channel permissions, membership, and privacy settings. Server owners and
          moderators can access moderation and audit information for communities they manage.
          Public or invite-accessible spaces may expose content to a wider audience.
        </p>
      </Section>

      <Section title="5. Service providers and external recipients">
        <p>Depending on the feature and deployment configuration, data may be handled by:</p>
        <ul className="list-disc space-y-2 pl-5">
          <li>Cloudinary for encrypted attachments and profile media storage.</li>
          <li>OrangMove for temporary storage of large files, normally for up to one hour.</li>
          <li>LiveKit infrastructure for real-time voice, video, and screen sharing.</li>
          <li>Google Firebase Cloud Messaging and Web Push providers for notifications.</li>
          <li>OpenAI for automated safety classification of uploaded images.</li>
          <li>
            Login and profile connection providers you select, such as Google, Discord, GitHub,
            GitLab, Twitch, YouTube, Reddit, X, or Steam.
          </li>
          <li>Infrastructure, database, caching, and security providers needed to host OrangChat.</li>
        </ul>
        <p>
          We disclose information to authorities or other parties when required by law or
          reasonably necessary to protect users, rights, safety, and service security.
        </p>
      </Section>

      <Section title="6. International transfers">
        <p>
          Some providers may process data outside Lithuania or the European Economic Area. Where
          required, we rely on an adequacy decision, approved standard contractual clauses, or
          another lawful transfer safeguard. You may ask us for information about the safeguard
          relevant to a particular provider.
        </p>
      </Section>

      <Section title="7. Retention">
        <ul className="list-disc space-y-2 pl-5">
          <li>Account and service content remain while your account or the relevant content exists.</li>
          <li>Refresh sessions normally expire after 30 days and are rotated or revoked on logout.</li>
          <li>Large OrangMove attachments normally expire within one hour.</li>
          <li>Temporary OAuth and QR login state normally expires within minutes.</li>
          <li>Operational, security, and moderation records are kept only as long as reasonably needed.</li>
          <li>Backups may retain deleted data for a limited recovery and security period.</li>
        </ul>
        <p>
          Deleting an account scrubs identifying account and profile fields and removes personal
          side records. Messages and necessary moderation records may remain under a deleted
          identity. You can delete all messages separately before deleting the account.
        </p>
      </Section>

      <Section title="8. Your rights">
        <p>
          Subject to applicable law, you may request access, correction, deletion, restriction,
          portability, or a copy of your data. You may object to processing based on legitimate
          interests and withdraw consent where processing relies on consent. You may also complain
          to the Lithuanian State Data Protection Inspectorate or your local supervisory authority.
        </p>
        <p>
          Use account and security settings where possible, or email us. We may need to verify
          your identity before completing a request.
        </p>
      </Section>

      <Section title="9. Children">
        <p>
          OrangChat is not available to children under 14. We do not knowingly collect personal
          data from a child below the applicable minimum age. Contact us if you believe a child
          has provided data in breach of this rule.
        </p>
      </Section>

      <Section title="10. Security">
        <p>
          Connections to OrangChat use encrypted HTTPS or WSS transport. Passwords are hashed
          with Argon2id rather than stored as readable passwords. Cloud-hosted message attachments
          and stored OAuth credentials are encrypted before storage. We also use access controls,
          short-lived access tokens, refresh-token rotation, optional two-factor authentication,
          rate limits, and restricted service permissions.
        </p>
        <p>
          Direct messages and group direct messages are end-to-end encrypted once every
          participant has enrolled an encryption-capable device. Until then, the conversation is
          clearly labelled as plaintext. Server channels remain readable by OrangChat so their
          public history, search, moderation, and role-based access controls can work. Encrypted
          conversations keep message bodies, attachment keys, drafts, and search data on
          participating devices; OrangChat still processes delivery metadata such as participants,
          timestamps, and ciphertext sizes. No system is perfectly secure, so users should also
          protect their credentials and devices.
        </p>
      </Section>

      <Section title="11. Changes">
        <p>
          We may update this policy when our service or legal obligations change. Significant
          changes will be communicated through the service or another appropriate channel.
        </p>
      </Section>
    </>
  );
}

function Cookies() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">Effective {EFFECTIVE_DATE}</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">Cookie and Local Storage Policy</h1>
        <p className="mt-3 leading-7 text-ink-secondary">
          OrangChat uses only storage needed to sign you in, secure account linking, remember your
          choices, and provide requested features. We do not use advertising or analytics cookies.
        </p>
      </header>

      <Section title="Cookies we use">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[36rem] text-left">
            <thead className="border-b border-border text-ink">
              <tr>
                <th className="p-2">Name</th>
                <th className="p-2">Purpose</th>
                <th className="p-2">Typical duration</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              <tr>
                <td className="p-2 font-mono text-xs">oc_refresh</td>
                <td className="p-2">Keeps your account signed in and rotates sessions securely.</td>
                <td className="p-2">Up to 30 days</td>
              </tr>
              <tr>
                <td className="p-2 font-mono text-xs">oc_oauth_state</td>
                <td className="p-2">Prevents forgery during Google or Discord login.</td>
                <td className="p-2">About 10 minutes</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          These cookies are strictly necessary to provide authentication that you request. They
          are Secure in production, and authentication cookies are not available to page scripts.
        </p>
      </Section>

      <Section title="Local device storage">
        <p>
          The web and desktop apps store interface preferences on your device, including theme,
          accessibility, message density, notification choices, plugin settings, GIF favourites,
          download prompts, and dismissed notices. The Android app stores equivalent settings and
          encrypted sign-in credentials in app storage. This information stays on your device
          unless a feature explicitly synchronises it with your account.
        </p>
      </Section>

      <Section title="Managing storage">
        <p>
          You can clear OrangChat site data through your browser or app settings. Blocking the
          necessary authentication cookie prevents persistent sign-in. Clearing local storage
          resets device preferences but does not delete your OrangChat account or server content.
          Because we do not set optional advertising or analytics cookies, OrangChat does not
          display a consent banner.
        </p>
      </Section>
    </>
  );
}

function Guidelines() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">Effective {EFFECTIVE_DATE}</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">Community Guidelines</h1>
        <p className="mt-3 leading-7 text-ink-secondary">
          OrangChat is built for conversation and community. These rules apply to accounts,
          profiles, messages, calls, files, servers, links, and every other part of the service.
        </p>
      </header>

      <Section title="Protect people">
        <ul className="list-disc space-y-2 pl-5">
          <li>No credible threats, encouragement of violence, or celebration of violent harm.</li>
          <li>No harassment, stalking, targeted humiliation, or coordinated abuse.</li>
          <li>No hateful conduct based on protected characteristics.</li>
          <li>No sharing private or intimate information without permission.</li>
          <li>No sexual exploitation, grooming, or endangerment of minors.</li>
          <li>No non-consensual intimate content or sexual content involving minors.</li>
        </ul>
      </Section>

      <Section title="Keep the service trustworthy">
        <ul className="list-disc space-y-2 pl-5">
          <li>No impersonation intended to deceive or defraud.</li>
          <li>No scams, phishing, malware, credential theft, or malicious links.</li>
          <li>No spam, unsolicited bulk messaging, artificial engagement, or abusive bots.</li>
          <li>No evasion of bans, moderation, rate limits, or security controls.</li>
          <li>No promotion or coordination of unlawful goods, services, or conduct.</li>
          <li>No infringement of copyright, trademark, privacy, or other rights.</li>
        </ul>
      </Section>

      <Section title="Server responsibilities">
        <p>
          Server owners should publish clear local rules, choose trustworthy moderators, use
          permissions carefully, respond to reports, and protect younger members. Local rules may
          be stricter than these guidelines but may not authorise conduct prohibited by them.
        </p>
      </Section>

      <Section title="Reporting and enforcement">
        <p>
          Report serious violations to server moderators and to{" "}
          <a className="oc-link" href={`mailto:${CONTACT_EMAIL}`}>
            {CONTACT_EMAIL}
          </a>
          . Include relevant message links, user or server identifiers, timestamps, and a concise
          explanation. Do not redistribute harmful material merely to document a report.
        </p>
        <p>
          Enforcement may include warnings, content restrictions, content removal, feature
          limits, suspension, termination, evidence preservation, or referral to authorities.
          We consider context, severity, intent, harm, history, and the risk of repetition.
        </p>
      </Section>

      <Section title="Appeals">
        <p>
          To request review of a platform-level action, email us from the address associated with
          your account. Explain the action, why you believe it should change, and any relevant
          context. Another review does not guarantee reversal.
        </p>
      </Section>
    </>
  );
}

function Notice() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">Updated {EFFECTIVE_DATE}</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">Legal Notice</h1>
      </header>

      <Section title="Service operator">
        <dl className="grid gap-2 sm:grid-cols-[10rem_1fr]">
          <dt className="font-medium text-ink">Service</dt>
          <dd>OrangChat</dd>
          <dt className="font-medium text-ink">Operator</dt>
          <dd>Oranges.LT</dd>
          <dt className="font-medium text-ink">Location</dt>
          <dd>Lithuania</dd>
          <dt className="font-medium text-ink">Email</dt>
          <dd>
            <a className="oc-link" href={`mailto:${CONTACT_EMAIL}`}>
              {CONTACT_EMAIL}
            </a>
          </dd>
          <dt className="font-medium text-ink">Website</dt>
          <dd>
            <a className="oc-link" href="https://chat.oranges.lt">
              chat.oranges.lt
            </a>
          </dd>
        </dl>
      </Section>

      <Section title="Legal and safety requests">
        <p>
          Send privacy requests, content notices, intellectual property notices, safety reports,
          and lawful authority requests to the email above. Clearly identify the request, the
          relevant content or account, your authority or rights, and reliable contact details.
          We may request additional information needed to verify and process the request.
        </p>
      </Section>

      <Section title="Copyright">
        <p>
          The OrangChat name, software, interface, graphics, and original materials are protected
          by applicable intellectual property law. User content remains the responsibility and
          property of its respective owner, subject to the licence in the Terms of Service.
        </p>
      </Section>
    </>
  );
}

const CONTENT: Record<LegalDocument, () => ReactNode> = {
  terms: Terms,
  privacy: Privacy,
  cookies: Cookies,
  guidelines: Guidelines,
  notice: Notice,
};

export function LegalPage({ document }: { document: LegalDocument }) {
  const Content = CONTENT[document];

  return (
    <div className="min-h-dvh bg-surface-0 text-ink">
      <header className="sticky top-0 z-10 border-b border-border bg-surface-0/95 backdrop-blur">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-4 px-5 py-3">
          <Link to="/" className="mr-auto flex items-center gap-2 font-bold">
            <LogoMark className="size-8" />
            <span>
              Orang<span className="text-primary">Chat</span>
            </span>
          </Link>
          <nav aria-label="Legal documents" className="flex flex-wrap gap-1">
            {DOCUMENTS.map((item) => (
              <NavLink
                key={item.id}
                to={item.path}
                className={({ isActive }) =>
                  cn(
                    "rounded-lg px-2.5 py-1.5 text-xs font-medium text-ink-muted hover:bg-surface-3 hover:text-ink",
                    isActive && "bg-primary-soft text-primary",
                  )
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        </div>
      </header>

      <main className="mx-auto max-w-3xl space-y-10 px-5 py-12">
        <Content />
      </main>

      <footer className="border-t border-border">
        <div className="mx-auto flex max-w-3xl flex-wrap items-center justify-between gap-3 px-5 py-6 text-xs text-ink-muted">
          <span>© 2026 Oranges.LT</span>
          <Link className="hover:text-ink" to="/">
            Back to OrangChat
          </Link>
        </div>
      </footer>
    </div>
  );
}
