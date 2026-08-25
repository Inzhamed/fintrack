variable "compartment_id" {
  description = "OCID of the compartment to build in."
  type        = string
}

variable "name_prefix" {
  description = "Prefix for every created resource, so they are identifiable in the console."
  type        = string
  default     = "fintrack"
}

variable "availability_domain" {
  description = "Availability domain for the instance."
  type        = string
}

variable "ssh_public_key" {
  description = "Public key authorised for the default user. Password auth is disabled."
  type        = string

  validation {
    condition     = can(regex("^(ssh-rsa|ssh-ed25519|ecdsa-)", trimspace(var.ssh_public_key)))
    error_message = "Must be a public key in OpenSSH format. Never paste a private key here."
  }
}

variable "ssh_allowed_cidr" {
  description = <<-EOT
    Who may reach SSH. Defaults to nobody, deliberately: 0.0.0.0/0 on port 22 attracts
    credential-stuffing within minutes of an instance appearing. Set it to your own address.
  EOT
  type        = string
  default     = "127.0.0.1/32"

  validation {
    condition     = can(cidrhost(var.ssh_allowed_cidr, 0))
    error_message = "Must be a valid CIDR block, for example 41.100.0.0/16."
  }
}

variable "instance_shape" {
  description = "Compute shape. The A1.Flex ARM shape is the one covered by Always Free."
  type        = string
  default     = "VM.Standard.A1.Flex"
}

variable "instance_ocpus" {
  description = "OCPUs. Always Free allows up to 4 across all A1 instances."
  type        = number
  default     = 2

  validation {
    condition     = var.instance_ocpus >= 1 && var.instance_ocpus <= 4
    error_message = "Always Free covers at most 4 OCPUs in total; more will be billed."
  }
}

variable "instance_memory_gb" {
  description = "Memory in GB. Always Free allows up to 24 across all A1 instances."
  type        = number
  default     = 12

  validation {
    condition     = var.instance_memory_gb >= 1 && var.instance_memory_gb <= 24
    error_message = "Always Free covers at most 24 GB in total; more will be billed."
  }
}

variable "boot_volume_gb" {
  description = "Boot volume size. Always Free includes 200 GB of block storage in total."
  type        = number
  default     = 50
}

variable "image_id" {
  description = "OCID of the OS image. Must be an aarch64 build to match the A1 shape."
  type        = string
}

variable "vcn_cidr" {
  description = "Address range for the virtual network."
  type        = string
  default     = "10.0.0.0/16"
}

variable "subnet_cidr" {
  description = "Address range for the public subnet."
  type        = string
  default     = "10.0.1.0/24"
}

variable "tags" {
  description = "Freeform tags applied to everything, so costs and ownership are traceable."
  type        = map(string)
  default     = {}
}
