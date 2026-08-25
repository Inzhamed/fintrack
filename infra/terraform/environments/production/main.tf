terraform {
  required_version = ">= 1.9"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
  }

  # State in Object Storage, not on a laptop.
  #
  # Local state means whoever ran apply last is the only one who can safely run it again, and
  # losing the machine loses the record of what exists. The bucket is created by hand once
  # before the first init - bootstrapping it from the configuration it stores is circular.
  #
  # Commented out so `validate` runs without credentials; uncomment for a real apply.
  # backend "s3" {
  #   bucket                      = "fintrack-tfstate"
  #   key                         = "production/terraform.tfstate"
  #   region                      = "eu-frankfurt-1"
  #   endpoints                   = { s3 = "https://<namespace>.compat.objectstorage.eu-frankfurt-1.oraclecloud.com" }
  #   skip_region_validation      = true
  #   skip_credentials_validation = true
  #   skip_requesting_account_id  = true
  #   skip_s3_checksum            = true
  #   use_path_style              = true
  # }
}

provider "oci" {
  region = var.region
}

module "k3s_node" {
  source = "../../modules/oci-k3s-node"

  compartment_id      = var.compartment_id
  availability_domain = var.availability_domain
  image_id            = var.image_id

  name_prefix      = "fintrack"
  ssh_public_key   = var.ssh_public_key
  ssh_allowed_cidr = var.ssh_allowed_cidr

  # Half of the Always Free allowance, leaving room for a second instance later without
  # tipping the account into paid usage.
  instance_ocpus     = 2
  instance_memory_gb = 12
  boot_volume_gb     = 50

  tags = {
    Environment = "production"
  }
}
