variable "region" {
  description = "OCI region, e.g. eu-frankfurt-1."
  type        = string
}

variable "compartment_id" {
  description = "OCID of the compartment. The tenancy OCID works for a personal account."
  type        = string
}

variable "availability_domain" {
  description = "Availability domain name, e.g. abCd:EU-FRANKFURT-1-AD-1."
  type        = string
}

variable "image_id" {
  description = "OCID of an aarch64 Ubuntu image, which must match the ARM instance shape."
  type        = string
}

variable "ssh_public_key" {
  description = "Public key authorised on the node."
  type        = string
}

variable "ssh_allowed_cidr" {
  description = "Address range permitted to reach SSH. Set this to your own address."
  type        = string
}
