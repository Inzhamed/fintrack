{{/* Chart name, overridable. */}}
{{- define "fintrack.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Release-qualified name.

Truncated to 63 characters because that is the limit for a Kubernetes name, and a Deployment
whose name overflows is rejected at apply time with an error that does not mention length.
*/}}
{{- define "fintrack.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Labels on every object, following the recommended Kubernetes set. */}}
{{- define "fintrack.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "fintrack.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: fintrack
{{- end -}}

{{/*
Selector labels for one component.

Deliberately excludes the chart and version labels: a Deployment's selector is immutable, so
including anything that changes between releases makes every upgrade fail with "field is
immutable" and require the Deployment to be deleted first.
*/}}
{{- define "fintrack.selectorLabels" -}}
app.kubernetes.io/name: {{ include "fintrack.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "fintrack.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "fintrack.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* The Secret holding credentials: either one supplied by the operator, or ours. */}}
{{- define "fintrack.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- printf "%s-secrets" (include "fintrack.fullname" .) -}}
{{- end -}}
{{- end -}}

{{/* Image reference, defaulting the tag to the chart's appVersion. */}}
{{- define "fintrack.image" -}}
{{- $tag := default .root.Chart.AppVersion .root.Values.image.tag -}}
{{- printf "%s/%s/%s:%s" .root.Values.image.registry .root.Values.image.repository .component $tag -}}
{{- end -}}

{{/* Hostnames of the backing services, in-cluster unless an external one is configured. */}}
{{- define "fintrack.postgresHost" -}}
{{- if .Values.postgres.externalHost -}}
{{- .Values.postgres.externalHost -}}
{{- else -}}
{{- printf "%s-postgres" (include "fintrack.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "fintrack.redisHost" -}}
{{- if .Values.redis.externalHost -}}
{{- .Values.redis.externalHost -}}
{{- else -}}
{{- printf "%s-redis" (include "fintrack.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "fintrack.storageHost" -}}
{{- printf "%s-storage" (include "fintrack.fullname" .) -}}
{{- end -}}
