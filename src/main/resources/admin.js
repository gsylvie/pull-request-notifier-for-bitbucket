define('plugin/prnfb/admin', [
 'jquery',
 '@atlassian/aui',
 'bitbucket/util/navbuilder',
 'plugin/prnfb/utils'
], function($, AJS, NAV, utils) {
 var settingsAdminUrlPostUrl = NAV.rest().build() + "/../../prnfb-admin/1.0/settings";
 var settingsAdminUrl = settingsAdminUrlPostUrl;

 var notificationsAdminUrlPostUrl = NAV.rest().build() + "/../../prnfb-admin/1.0/settings/notifications";
 var notificationsAdminUrl = notificationsAdminUrlPostUrl;

 var buttonsAdminUrlPostUrl = NAV.rest().build() + "/../../prnfb-admin/1.0/settings/buttons";
 var buttonsAdminUrl = buttonsAdminUrlPostUrl;

 var projectKey;
 if ($('#prnfbRepositorySlug').length !== 0) {
  projectKey = $('#prnfbProjectKey').val();
  var repositorySlug = $('#prnfbRepositorySlug').val();

  notificationsAdminUrl = notificationsAdminUrlPostUrl + '/projects/' + projectKey + '/repos/' + repositorySlug;
  buttonsAdminUrl = buttonsAdminUrlPostUrl + '/projects/' + projectKey + '/repos/' + repositorySlug;
 } else if ($('#prnfbProjectKey').length !== 0) {
  projectKey = $('#prnfbProjectKey').val();

  notificationsAdminUrl = notificationsAdminUrlPostUrl + '/projects/' + projectKey;
  buttonsAdminUrl = buttonsAdminUrlPostUrl + '/projects/' + projectKey;
 }

 $(document)
  .ajaxStart(function() {
   $('.prnfb button').attr('aria-disabled', 'true');
  })
  .ajaxStop(function() {
   $('.prnfb button').attr('aria-disabled', 'false');
  });

 $(document).ready(function() {
  utils.setupForm('#prnfbsettingsadmin', settingsAdminUrl, settingsAdminUrlPostUrl);
  utils.setupForms('#prnfbbuttonadmin', buttonsAdminUrl, buttonsAdminUrlPostUrl);
  utils.setupForms('#prnfbnotificationadmin', notificationsAdminUrl, notificationsAdminUrlPostUrl);
 });
});


if (AJS && AJS.$) {
 AJS.$(document).ready(function() {
  require('plugin/prnfb/admin');
 });
} else {
 $(document).ready(function() {
  require('plugin/prnfb/admin');
 });
}
