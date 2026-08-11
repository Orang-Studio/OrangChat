import type { ReactNode } from "react";
import { Link, NavLink } from "react-router-dom";
import { LogoMark } from "../../components/LogoMark";
import { cn } from "../../lib/cn";
import { t, tNodes } from "../../lib/i18n";

export type LegalDocument =
  | "terms"
  | "privacy"
  | "cookies"
  | "guidelines"
  | "notice";

const EFFECTIVE_DATE = "25 July 2026";
const CONTACT_EMAIL = "admin@oranges.lt";

function documents(): Array<{ id: LegalDocument; label: string; path: string }> {
  return [
    { id: "terms", label: t("legalPage.navTerms"), path: "/terms" },
    { id: "privacy", label: t("legalPage.navPrivacy"), path: "/privacy" },
    { id: "cookies", label: t("legalPage.navCookies"), path: "/cookies" },
    { id: "guidelines", label: t("legalPage.navGuidelines"), path: "/guidelines" },
    { id: "notice", label: t("legalPage.navLegalNotice"), path: "/legal-notice" },
  ];
}

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
        <p className="text-sm font-medium text-primary">
          {t("legalPage.effectiveDate", { date: EFFECTIVE_DATE })}
        </p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">{t("legalPage.termsOfService")}</h1>
        <p className="mt-3 leading-7 text-ink-secondary">
          {t("legalPage.theseTermsGovernYourUseOf")}
        </p>
      </header>

      <Section title={t("legalPage.1WhoProvidesOrangchat")}>
        <p>
          {tNodes("legalPage.operatedByOrangesLt", {
            email: (
              <a className="oc-link" href={`mailto:${CONTACT_EMAIL}`}>
                {CONTACT_EMAIL}
              </a>
            ),
            legalNotice: (
              <Link className="oc-link" to="/legal-notice">
                {t("legalPage.legalNotice")}
              </Link>
            ),
          })}
        </p>
      </Section>

      <Section title={t("legalPage.2AcceptingTheseTerms")}>
        <p>
          {t("legalPage.byCreatingAnAccountAccessingOrangchat")}
        </p>
        <p>
          {t("legalPage.youMustBeAtLeast14")}
        </p>
      </Section>

      <Section title={t("legalPage.3YourAccount")}>
        <ul className="list-disc space-y-2 pl-5">
          <li>{t("legalPage.provideAccurateRegistrationInformationAndKeep")}</li>
          <li>{t("legalPage.keepYourPasswordBackupCodesDevices")}</li>
          <li>{t("legalPage.doNotSellTransferShareOr")}</li>
          <li>{t("legalPage.tellUsPromptlyIfYouBelieve")}</li>
          <li>{t("legalPage.youAreResponsibleForActivityPerformed")}</li>
        </ul>
      </Section>

      <Section title={t("legalPage.4YourContent")}>
        <p>
          {t("legalPage.youKeepOwnershipOfMessagesFiles")}
        </p>
        <p>
          {t("legalPage.youConfirmThatYouHaveThe")}
        </p>
      </Section>

      <Section title={t("legalPage.5RulesForUsingTheService")}>
        <p>{t("legalPage.youMustFollowTheCommunityGuidelines")}</p>
        <ul className="list-disc space-y-2 pl-5">
          <li>{t("legalPage.useOrangchatForIllegalFraudulentDeceptive")}</li>
          <li>{t("legalPage.exploitGroomEndangerOrSexualiseA")}</li>
          <li>{t("legalPage.threatenViolenceHarassPeoplePublishPrivate")}</li>
          <li>{t("legalPage.distributeMalwareStealCredentialsEvadeAccess")}</li>
          <li>{t("legalPage.sendSpamRunAbusiveAutomationScrape")}</li>
          <li>{t("legalPage.uploadContentYouDoNotHave")}</li>
          <li>{t("legalPage.evadeASuspensionBanRateLimit")}</li>
        </ul>
      </Section>

      <Section title={t("legalPage.6ServersAndModeration")}>
        <p>
          {t("legalPage.serverOwnersAndModeratorsMaySet")}
        </p>
        <p>
          {t("legalPage.weAimToApplyRestrictionsDiligently")}
        </p>
      </Section>

      <Section title={t("legalPage.7SoftwareAndThirdPartyServices")}>
        <p>
          {t("legalPage.weGiveYouAPersonalLimited")}
        </p>
        <p>
          {t("legalPage.orangchatCanConnectToOrDisplay")}
        </p>
      </Section>

      <Section title={t("legalPage.8AvailabilityAndChanges")}>
        <p>
          {t("legalPage.orangchatIsCurrentlyProvidedWithoutA")}
        </p>
      </Section>

      <Section title={t("legalPage.9SuspensionAndTermination")}>
        <p>
          {t("legalPage.youMayStopUsingOrangchatAt")}
        </p>
        <p>
          {t("legalPage.weMaySuspendOrTerminateAccess")}
        </p>
      </Section>

      <Section title={t("legalPage.10DisclaimersAndLiability")}>
        <p>
          {t("legalPage.toTheFullestExtentPermittedBy")}
        </p>
        <p>
          {t("legalPage.nothingInTheseTermsExcludesLiability")}
        </p>
      </Section>

      <Section title={t("legalPage.11GoverningLawAndDisputes")}>
        <p>
          {t("legalPage.lithuanianLawGovernsTheseTermsWithout")}
        </p>
      </Section>

      <Section title={t("legalPage.12ChangesToTheseTerms")}>
        <p>
          {t("legalPage.weMayUpdateTheseTermsFor")}
        </p>
      </Section>
    </>
  );
}

function Privacy() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">
          {t("legalPage.effectiveDate", { date: EFFECTIVE_DATE })}
        </p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">{t("legalPage.privacyPolicy")}</h1>
        <p className="mt-3 leading-7 text-ink-secondary">
          {t("legalPage.orangchatIsPrivacyFocusedWeDo")}
        </p>
      </header>

      <Section title={t("legalPage.1ControllerAndContact")}>
        <p>
          {tNodes("legalPage.controllerContactParagraph", {
            email: (
              <a className="oc-link" href={`mailto:${CONTACT_EMAIL}`}>
                {CONTACT_EMAIL}
              </a>
            ),
          })}
        </p>
      </Section>

      <Section title={t("legalPage.2DataWeProcess")}>
        <p>
          {t("legalPage.weMinimiseDataCollectionIfInformation")}
        </p>
        <ul className="list-disc space-y-2 pl-5">
          <li>
            <strong>{t("legalPage.accountData")}</strong> {t("legalPage.emailAddressUsernameDisplayNamePassword")}
          </li>
          <li>
            <strong>{t("legalPage.contentAndSocialData")}</strong> {t("legalPage.messagesAttachmentsReactionsDraftsFriendshipsMemberships")}
          </li>
          <li>
            <strong>{t("legalPage.voiceAndPresenceData")}</strong> {t("legalPage.callParticipationOnlineStatusDeviceType")}
          </li>
          <li>
            <strong>{t("legalPage.securityAndTechnicalData")}</strong> {t("legalPage.ipAddressUserAgentSessionRecords")}
          </li>
          <li>
            <strong>{t("legalPage.deviceData")}</strong> {t("legalPage.locallyStoredThemeAccessibilityNotificationDownload")}
          </li>
          <li>
            <strong>{t("legalPage.integrationData")}</strong> {t("legalPage.identifiersNamesProfileLinksAndOauth")}
          </li>
        </ul>
      </Section>

      <Section title={t("legalPage.3WhyWeProcessData")}>
        <ul className="list-disc space-y-2 pl-5">
          <li>
            <strong>{t("legalPage.contract")}</strong> {t("legalPage.toCreateAccountsDeliverMessagesAnd")}
          </li>
          <li>
            <strong>{t("legalPage.legitimateInterests")}</strong> {t("legalPage.toSecureTheServicePreventAbuse")}
          </li>
          <li>
            <strong>{t("legalPage.legalObligations")}</strong> {t("legalPage.toRespondToValidLegalRequests")}
          </li>
          <li>
            <strong>{t("legalPage.consent")}</strong> {t("legalPage.whereYouChooseOptionalDevicePermissions")}
          </li>
        </ul>
        <p>
          {t("legalPage.orangchatHasNoAdvertisingAnalyticsSdk")}
        </p>
      </Section>

      <Section title={t("legalPage.4HowContentIsShared")}>
        <p>
          {t("legalPage.messagesAndProfileInformationAreShared")}
        </p>
      </Section>

      <Section title={t("legalPage.5ServiceProvidersAndExternalRecipients")}>
        <p>{t("legalPage.dependingOnTheFeatureAndDeployment")}</p>
        <ul className="list-disc space-y-2 pl-5">
          <li>{t("legalPage.cloudinaryForEncryptedAttachmentsAndProfile")}</li>
          <li>{t("legalPage.orangmoveForTemporaryStorageOfLarge")}</li>
          <li>{t("legalPage.livekitInfrastructureForRealTimeVoice")}</li>
          <li>{t("legalPage.googleFirebaseCloudMessagingAndWeb")}</li>
          <li>{t("legalPage.openaiForAutomatedSafetyClassificationOf")}</li>
          <li>
            {t("legalPage.loginAndProfileConnectionProvidersYou")}
          </li>
          <li>{t("legalPage.infrastructureDatabaseCachingAndSecurityProviders")}</li>
        </ul>
        <p>
          {t("legalPage.weDiscloseInformationToAuthoritiesOr")}
        </p>
      </Section>

      <Section title={t("legalPage.6InternationalTransfers")}>
        <p>
          {t("legalPage.someProvidersMayProcessDataOutside")}
        </p>
      </Section>

      <Section title={t("legalPage.7Retention")}>
        <ul className="list-disc space-y-2 pl-5">
          <li>{t("legalPage.accountAndServiceContentRemainWhile")}</li>
          <li>{t("legalPage.refreshSessionsNormallyExpireAfter30")}</li>
          <li>{t("legalPage.largeOrangmoveAttachmentsNormallyExpireWithin")}</li>
          <li>{t("legalPage.temporaryOauthAndQrLoginState")}</li>
          <li>{t("legalPage.operationalSecurityAndModerationRecordsAre")}</li>
          <li>{t("legalPage.backupsMayRetainDeletedDataFor")}</li>
        </ul>
        <p>
          {t("legalPage.deletingAnAccountScrubsIdentifyingAccount")}
        </p>
      </Section>

      <Section title={t("legalPage.8YourRights")}>
        <p>
          {t("legalPage.subjectToApplicableLawYouMay")}
        </p>
        <p>
          {t("legalPage.useAccountAndSecuritySettingsWhere")}
        </p>
      </Section>

      <Section title={t("legalPage.9Children")}>
        <p>
          {t("legalPage.orangchatIsNotAvailableToChildren")}
        </p>
      </Section>

      <Section title={t("legalPage.10Security")}>
        <p>
          {t("legalPage.connectionsToOrangchatUseEncryptedHttps")}
        </p>
        <p>
          {t("legalPage.directMessagesAndGroupDirectMessages")}
        </p>
      </Section>

      <Section title={t("legalPage.11Changes")}>
        <p>
          {t("legalPage.weMayUpdateThisPolicyWhen")}
        </p>
      </Section>
    </>
  );
}

function Cookies() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">
          {t("legalPage.effectiveDate", { date: EFFECTIVE_DATE })}
        </p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">{t("legalPage.cookieAndLocalStoragePolicy")}</h1>
        <p className="mt-3 leading-7 text-ink-secondary">
          {t("legalPage.orangchatUsesOnlyStorageNeededTo")}
        </p>
      </header>

      <Section title={t("legalPage.cookiesWeUse")}>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[36rem] text-left">
            <thead className="border-b border-border text-ink">
              <tr>
                <th className="p-2">{t("legalPage.name")}</th>
                <th className="p-2">{t("legalPage.purpose")}</th>
                <th className="p-2">{t("legalPage.typicalDuration")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              <tr>
                <td className="p-2 font-mono text-xs">oc_refresh</td>
                <td className="p-2">{t("legalPage.keepsYourAccountSignedInAnd")}</td>
                <td className="p-2">{t("legalPage.upTo30Days")}</td>
              </tr>
              <tr>
                <td className="p-2 font-mono text-xs">oc_oauth_state</td>
                <td className="p-2">{t("legalPage.preventsForgeryDuringGoogleOrDiscord")}</td>
                <td className="p-2">{t("legalPage.about10Minutes")}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          {t("legalPage.theseCookiesAreStrictlyNecessaryTo")}
        </p>
      </Section>

      <Section title={t("legalPage.localDeviceStorage")}>
        <p>
          {t("legalPage.theWebAndDesktopAppsStore")}
        </p>
      </Section>

      <Section title={t("legalPage.managingStorage")}>
        <p>
          {t("legalPage.youCanClearOrangchatSiteData")}
        </p>
      </Section>
    </>
  );
}

function Guidelines() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">
          {t("legalPage.effectiveDate", { date: EFFECTIVE_DATE })}
        </p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">{t("legalPage.communityGuidelines")}</h1>
        <p className="mt-3 leading-7 text-ink-secondary">
          {t("legalPage.orangchatIsBuiltForConversationAnd")}
        </p>
      </header>

      <Section title={t("legalPage.protectPeople")}>
        <ul className="list-disc space-y-2 pl-5">
          <li>{t("legalPage.noCredibleThreatsEncouragementOfViolence")}</li>
          <li>{t("legalPage.noHarassmentStalkingTargetedHumiliationOr")}</li>
          <li>{t("legalPage.noHatefulConductBasedOnProtected")}</li>
          <li>{t("legalPage.noSharingPrivateOrIntimateInformation")}</li>
          <li>{t("legalPage.noSexualExploitationGroomingOrEndangerment")}</li>
          <li>{t("legalPage.noNonConsensualIntimateContentOr")}</li>
        </ul>
      </Section>

      <Section title={t("legalPage.keepTheServiceTrustworthy")}>
        <ul className="list-disc space-y-2 pl-5">
          <li>{t("legalPage.noImpersonationIntendedToDeceiveOr")}</li>
          <li>{t("legalPage.noScamsPhishingMalwareCredentialTheft")}</li>
          <li>{t("legalPage.noSpamUnsolicitedBulkMessagingArtificial")}</li>
          <li>{t("legalPage.noEvasionOfBansModerationRate")}</li>
          <li>{t("legalPage.noPromotionOrCoordinationOfUnlawful")}</li>
          <li>{t("legalPage.noInfringementOfCopyrightTrademarkPrivacy")}</li>
        </ul>
      </Section>

      <Section title={t("legalPage.serverResponsibilities")}>
        <p>
          {t("legalPage.serverOwnersShouldPublishClearLocal")}
        </p>
      </Section>

      <Section title={t("legalPage.reportingAndEnforcement")}>
        <p>
          {tNodes("legalPage.reportSeriousViolations", {
            email: (
              <a className="oc-link" href={`mailto:${CONTACT_EMAIL}`}>
                {CONTACT_EMAIL}
              </a>
            ),
          })}
        </p>
        <p>
          {t("legalPage.enforcementMayIncludeWarningsContentRestrictions")}
        </p>
      </Section>

      <Section title={t("legalPage.appeals")}>
        <p>
          {t("legalPage.toRequestReviewOfAPlatform")}
        </p>
      </Section>
    </>
  );
}

function Notice() {
  return (
    <>
      <header>
        <p className="text-sm font-medium text-primary">
          {t("legalPage.updatedDate", { date: EFFECTIVE_DATE })}
        </p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">{t("legalPage.legalNotice")}</h1>
      </header>

      <Section title={t("legalPage.serviceOperator")}>
        <dl className="grid gap-2 sm:grid-cols-[10rem_1fr]">
          <dt className="font-medium text-ink">{t("legalPage.service")}</dt>
          <dd>{t("legalPage.orangchat")}</dd>
          <dt className="font-medium text-ink">{t("legalPage.operator")}</dt>
          <dd>{t("legalPage.orangesLt")}</dd>
          <dt className="font-medium text-ink">{t("legalPage.location")}</dt>
          <dd>{t("legalPage.lithuania")}</dd>
          <dt className="font-medium text-ink">{t("legalPage.email")}</dt>
          <dd>
            <a className="oc-link" href={`mailto:${CONTACT_EMAIL}`}>
              {CONTACT_EMAIL}
            </a>
          </dd>
          <dt className="font-medium text-ink">{t("legalPage.website")}</dt>
          <dd>
            <a className="oc-link" href="https://chat.oranges.lt">
              chat.oranges.lt
            </a>
          </dd>
        </dl>
      </Section>

      <Section title={t("legalPage.legalAndSafetyRequests")}>
        <p>
          {t("legalPage.sendPrivacyRequestsContentNoticesIntellectual")}
        </p>
      </Section>

      <Section title={t("legalPage.copyright")}>
        <p>
          {t("legalPage.theOrangchatNameSoftwareInterfaceGraphics")}
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
              {t("legalPage.orang")}<span className="text-primary">{t("legalPage.chat")}</span>
            </span>
          </Link>
          <nav aria-label={t("legalPage.legalDocuments")} className="flex flex-wrap gap-1">
            {documents().map((item) => (
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
          <span>{t("legalPage.2026OrangesLt")}</span>
          <Link className="hover:text-ink" to="/">
            {t("legalPage.backToOrangchat")}
          </Link>
        </div>
      </footer>
    </div>
  );
}
